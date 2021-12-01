package de.tiramon.du.tools.thread;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.security.NoTypePermission;
import com.thoughtworks.xstream.security.NullPermission;
import com.thoughtworks.xstream.security.PrimitiveTypePermission;

import de.tiramon.du.tools.model.DuLogRecord;
import de.tiramon.du.tools.service.IHandleService;
import de.tiramon.du.tools.service.IMethodEnumConverter;

public class FileReader implements Runnable {
	protected Logger log = LoggerFactory.getLogger(getClass());

	private IHandleService handleService;
	private XStream xstream;

	// set to true when application is about to close so reading is stopped and no
	// exception is thrown
	private boolean shutdown = false;

	private List<Path> pathQueue = new CopyOnWriteArrayList<>();

	private boolean skipToEnd;

	private IMethodEnumConverter methodEnumConverter;

	public FileReader(IHandleService handleService, IMethodEnumConverter methodEnumConverter, Properties properties) {
		this.handleService = handleService;
		this.methodEnumConverter = methodEnumConverter;
		this.skipToEnd = Boolean.parseBoolean(properties.getProperty("skip.to.end", "false"));
	}

	@Override
	public void run() {
		log.info("FileReader started");
		setupXStream();
		while (!shutdown) {
			while (pathQueue.isEmpty()) {
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			Path path = pathQueue.remove(0);
			handleService.setBacklogCount(pathQueue.size());
			log.info("new log file {}", path);
			readFile(path);
		}
	}

	private void setupXStream() {
		xstream = new XStream();
		// clear out existing permissions and set own ones
		xstream.addPermission(NoTypePermission.NONE);
		// allow some basics
		xstream.addPermission(NullPermission.NULL);
		xstream.addPermission(PrimitiveTypePermission.PRIMITIVES);
		xstream.allowTypeHierarchy(Collection.class);
		// allow any type from the same package
		xstream.allowTypesByWildcard(new String[] { "de.tiramon.du.**.model.**" });

		// specific mapping, record also exists as java.util, so mapping to a unique
		// class name
		xstream.alias("record", DuLogRecord.class);
		// class is a reserved word in java so we need to map it to something that is
		// not reserved
		xstream.aliasAttribute(DuLogRecord.class, "clazz", "class");
		xstream.registerConverter(methodEnumConverter);
	}

	public void onShutdown() {
		shutdown = true;
	}

	private void readFile(Path path) {
		log.info("start reading logfile {}", path);
		if (path.getFileName().toString().endsWith(".lnk")) {
			return;
		}
		if (!path.toFile().isFile()) {
			return;
		}
		String line;
		List<String> lineBuffer = new ArrayList<>();
		long start = System.currentTimeMillis();
		handleService.setCurrentLogfileName(path);
		try (BufferedReader br = Files.newBufferedReader(path)) {
			if (skipToEnd) {
				log.info("skipping");
				long skip = Long.MAX_VALUE;
				long skiped;

				do {
					skiped = br.skip(skip);
					log.info("skiped {}", skiped);
				} while (skiped == skip);
				skipToEnd = false;
				handleService.setInitialized(true);
				log.info("skiping done");
			} else {
				log.info("no skipping");
			}
			log.info("start interpreting entries");
			while (true) {
				long lastModified = path.toFile().lastModified();
				// if (lastModified < System.currentTimeMillis() - 30 * 1000) {
				// log.info("stop reading file, because it wasn't changed for 30s");
				// return;
				// }

				if (shutdown) {
					log.info("shutdown");
					handleService.setWorking(false);
					return;
				}

				// if (!currentLogFileProperty.get().equals(path)) {
				// log.info("stoping reading old logfile");
				// return;
				// }
				line = br.readLine();

				if (line == null) {
					handleService.setInitialized(true);
					synchronized (pathQueue) {
						if (!pathQueue.isEmpty()) {
							log.info("current file done and new present");
							return;
						}
					}
					handleService.setWorking(false);
					Thread.sleep(100);
				} else {
					handleService.setWorking(true);
					line = line.trim();
					// if end of record entry is reached, parse and process
					if (line.equals("</record>")) {
						lineBuffer.add(line);
						DuLogRecord record = null;

						try {
							record = mapToRecord(lineBuffer);

						} catch (com.thoughtworks.xstream.converters.ConversionException e) {
							// System.out.println(convert(lineBuffer));
						}
						if (record != null) {
							final long timestamp = record.millis;
							handleService.setLastEntryRead(timestamp);
						}
						if (record != null && record.method != null) {

							this.handleService.handle(record);
						}
						// addAvgReadPerRecord(System.currentTimeMillis() - start);
						// publish record for further processing

						start = System.currentTimeMillis();
						// publishRecord(record);
						// addAvgProcessPerRecord(System.currentTimeMillis() - start);

						// clear buffer to start new entry
						lineBuffer.clear();

						start = System.currentTimeMillis();

					} else {
						// if not end of record add to buffer for later parsing
						lineBuffer.add(line);
					}
				}
			}
		} catch (Exception e) {
			System.err.println(convert(lineBuffer));
			handleService.setWorking(false);
			throw new RuntimeException(e);
		}
	}

	private DuLogRecord mapToRecord(List<String> lineBuffer) throws IOException, ClassNotFoundException {

		String str = convert(lineBuffer);
		// System.out.println(str);
		ObjectInputStream in = xstream.createObjectInputStream(new ByteArrayInputStream(str.getBytes()));
		DuLogRecord record = (DuLogRecord) in.readObject();
		return record;
	}

	private String convert(List<String> lineBuffer) {
		lineBuffer.add(0, "<wrapper>");
		lineBuffer.add("</wrapper>");
		String str = lineBuffer.stream().collect(Collectors.joining());
		str = replaceString(str);
		return str;
	}

	private String replaceString(String s) {
		//@formatter:off
		return s.replaceAll("&", "&amp;")
				.replaceAll("<>", "&lt;&gt;")
				.replaceAll("<lambda_[a-z0-9]+>", "&lt;lambda&gt;")
				.replaceAll("<((?:(?!wrapper|record|date|millis|sequence|logger|level|class|method|thread|message).)+?)>","&lt;$1&gt;")
				.replaceAll("<((?:(?!wrapper|record|date|millis|sequence|logger|level|class|method|thread|message).)+?)>","&lt;$1&gt;")
				.replaceAll("<class ","&lt;class ");
		//@formatter:on
	}

	public void stop() {
		shutdown = true;
	}

	public List<Path> getQueue() {
		return pathQueue;
	}

}
