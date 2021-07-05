package de.tiramon.du.tools.service;

import java.nio.file.Path;

import de.tiramon.du.tools.model.DuLogRecord;

public interface IHandleService {

	public void handle(DuLogRecord record);

	public void setCurrentLogfileName(Path newLogFile);

	public void setInitialized(boolean b);

	public void setWorking(boolean b);

	public void setLastEntryRead(long timestamp);

}
