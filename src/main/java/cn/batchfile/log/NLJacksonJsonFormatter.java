package cn.batchfile.log;

import java.io.IOException;
import java.util.Map;

import ch.qos.logback.contrib.jackson.JacksonJsonFormatter;

public class NLJacksonJsonFormatter extends JacksonJsonFormatter {
	
	@Override
	@SuppressWarnings("rawtypes")
	public String toJsonString(Map m) throws IOException {
		return super.toJsonString(m) + "\n";
	}

}
