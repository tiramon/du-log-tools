package de.tiramon.du.tools.thread;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tiramon.du.tools.exception.NoDuLogFolderException;
import de.tiramon.du.tools.service.IHandleService;

public class NewFileWatcher implements Runnable {
	protected Logger log = LoggerFactory.getLogger(getClass());

	private IHandleService handleService;
	private Path logFolder;

	private WatchKey folderKey;

	private List<Path> queue;

	public NewFileWatcher(List<Path> queue, Properties properties, IHandleService handleService) {
		this.queue = queue;
		this.handleService = handleService;

		Path homepath = Paths.get(System.getProperty("user.home"));
		logFolder = Paths.get(homepath.toString(), "\\AppData\\Local\\NQ\\DualUniverse\\log");

		boolean readAll = Boolean.parseBoolean((String) properties.getOrDefault("readAll", "false"));
		if (readAll) {
			try {
				// add all except newest logfile it will be added on run anyway
				List<Path> list = Files.list(logFolder).sorted((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified())).collect(Collectors.toList());
				list.remove(list.size() - 1);
				list.forEach(p -> this.queue.add(p));
				log.info("added {} to backlog", list.size());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void run() {
		log.info("LogFileWatcher started");
		File logFolderFile = logFolder.toFile();
		if (!logFolderFile.exists() || !logFolderFile.isDirectory()) {
			throw new NoDuLogFolderException("Dual Universe log folder '" + logFolderFile.getAbsolutePath() + "' does not exist");
		}
		try {
			Thread.sleep(500);

			WatchService watchService = FileSystems.getDefault().newWatchService();
			folderKey = logFolder.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
			log.info("registered DU log folder {}", logFolder);
			Path logFile = getNewestLogfile();

			if (logFile != null) {
				log.info("Setting newest log file as current log file: {}", logFile);
				queue.add(logFile);
			}

			WatchKey key = null;
			while ((key = watchService.take()) != null) {
				for (WatchEvent<?> event : key.pollEvents()) {
					// process
					Path affectedFile = (Path) event.context();
					// System.out.println("Event kind:" + event.kind() + ". File affected: " +
					// event.context() + ".");

					Path newLogFile = Paths.get(logFolder.toString(), affectedFile.toString());
					log.info("new file {}", newLogFile);
					this.queue.add(newLogFile);
				}
				key.reset();
			}

		} catch (IOException e) {
			throw new RuntimeException(e);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private Path getNewestLogfile() throws IOException {
		return Files.list(logFolder).filter(f -> f.toString().endsWith(".xml")).max((p1, p2) -> {
			try {
				FileTime t1 = Files.getLastModifiedTime(p1);
				FileTime t2 = Files.getLastModifiedTime(p2);
				return t1.compareTo(t2);
			} catch (IOException e) {
				e.printStackTrace();
				return -1;
			}
		}).orElse(null);
	}

	public void stop() {
		if (folderKey != null) {
			folderKey.cancel();
		}
	}
}
