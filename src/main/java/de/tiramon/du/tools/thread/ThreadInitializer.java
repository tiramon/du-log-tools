package de.tiramon.du.tools.thread;

import java.util.Properties;

import de.tiramon.du.tools.service.IHandleService;
import de.tiramon.du.tools.service.IMethodEnumConverter;

public class ThreadInitializer {
	private static FileReader fileReader;
	private static NewFileWatcher newFileWatcher;
	private static Thread fileReaderThread;
	private static Thread fileWatcherThread;

	public static void initThreads(IHandleService handlerService, IMethodEnumConverter methodEnumConverter, Properties properties) {
		Thread.UncaughtExceptionHandler h = new Thread.UncaughtExceptionHandler() {

			@Override
			public void uncaughtException(Thread t, Throwable e) {
				stopThreads();
				e.printStackTrace();
				System.exit(-1);
			}
		};
		fileReader = new FileReader(handlerService, methodEnumConverter, properties);

		newFileWatcher = new NewFileWatcher(fileReader.getQueue(), properties, handlerService);

		fileReaderThread = new Thread(fileReader, "FileReader");
		fileReaderThread.setUncaughtExceptionHandler(h);
		fileReaderThread.start();
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		fileWatcherThread = new Thread(newFileWatcher, "FileWatcher");
		fileWatcherThread.setUncaughtExceptionHandler(h);
		fileWatcherThread.start();
	}

	public static void stopThreads() {
		// log.info("stoping threads");
		if (newFileWatcher != null) {
			newFileWatcher.stop();
		}
		if (fileReader != null) {
			fileReader.stop();
		}

	}
}
