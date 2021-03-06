package cn.batchfile.log.service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Map;

import javax.activation.UnsupportedDataTypeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.alibaba.fastjson.JSONObject;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public class SocketNode implements Runnable {

	protected static final Logger logger = LoggerFactory.getLogger(SocketNode.class);
	private static final String DEFAULT_CONTEXT_NAME = "localclient";
	private Counter counter;
	private Counter unsupportedDataTypeCounter;
	
	Socket socket;
	ObjectInputStream ois;
	SocketAddress remoteSocketAddress;

	boolean closed = false;
	SocketService socketService;
	StoreService storeService;

	public SocketNode(SocketService socketService, Socket socket, StoreService storeService, MeterRegistry registry) {
		//注册指标
		counter = Counter.builder("event.count").register(registry);
		unsupportedDataTypeCounter = Counter.builder("event.unsupported.data.type.count").register(registry);
		
		this.socketService = socketService;
		this.socket = socket;
		this.storeService = storeService;
		remoteSocketAddress = socket.getRemoteSocketAddress();
	}

	public void run() {
		try {
			ois = new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));
		} catch (Exception e) {
			logger.error("Could not open ObjectInputStream to " + socket, e);
			closed = true;
		}

		Object event = null;

		try {
			while (!closed) {
				// read an event from the wire
				event = ois.readObject();
				JSONObject obj = null;

				// compose object
				if (event instanceof ILoggingEvent) {
					obj = composeLogObject((ILoggingEvent) event);
				} else if (event instanceof org.apache.log4j.spi.LoggingEvent) {
					obj = composeLogObject((org.apache.log4j.spi.LoggingEvent) event);
				} else {
					unsupportedDataTypeCounter.increment();
					throw new UnsupportedDataTypeException(event.getClass().getName());
				}
				
				if (remoteSocketAddress instanceof InetSocketAddress) {
					obj.put("host", ((InetSocketAddress) remoteSocketAddress).getHostString());
					obj.put("port", String.valueOf(((InetSocketAddress) remoteSocketAddress).getPort()));
				}
				
				// 设置计数器
				counter.increment();

				// store log object
				storeService.store(obj);
			}
		} catch (java.io.EOFException e) {
			logger.info("Caught java.io.EOFException closing connection.");
		} catch (java.net.SocketException e) {
			logger.info("Caught java.net.SocketException closing connection.");
		} catch (IOException e) {
			logger.info("Caught java.io.IOException: " + e);
			logger.info("Closing connection.");
		} catch (Exception e) {
			logger.error("Unexpected exception. Closing connection.", e);
		}

		socketService.socketNodeClosing(this);
		close();
	}

	private JSONObject composeLogObject(ILoggingEvent event) {
		JSONObject obj = new JSONObject();

		String contextName = event.getLoggerContextVO() == null ? null : event.getLoggerContextVO().getName();
		if (contextName == null || contextName.length() == 0) {
			contextName = DEFAULT_CONTEXT_NAME;
		}
		obj.put("timestamp", event.getTimeStamp());
		obj.put("message", event.getFormattedMessage());
		obj.put("level", event.getLevel().toString());
		obj.put("thread", event.getThreadName());
		obj.put("logger", event.getLoggerName());
		obj.put("context", contextName);
		String ex = composeException(event.getThrowableProxy());
		if (!StringUtils.isEmpty(ex)) {
			obj.put("exception", ex);
		}

		obj.put("mdc", event.getMDCPropertyMap());

		return obj;
	}

	private JSONObject composeLogObject(org.apache.log4j.spi.LoggingEvent event) {
		JSONObject obj = new JSONObject();

		Map<?, ?> properties = event.getProperties();
		Object application = properties.get("application");
		String contextName = application == null || application.toString().length() == 0 ? DEFAULT_CONTEXT_NAME
				: application.toString();

		obj.put("timestamp", event.getTimeStamp());
		obj.put("message", event.getRenderedMessage());
		obj.put("level", event.getLevel().toString());
		obj.put("thread", event.getThreadName());
		obj.put("logger", event.getLoggerName());
		obj.put("context", contextName);
		String ex = composeException(event.getThrowableInformation());
		if (!StringUtils.isEmpty(ex)) {
			obj.put("exception", ex);
		}

		obj.put("mdc", properties);

		return obj;
	}

	private String composeException(org.apache.log4j.spi.ThrowableInformation throwable) {
		if (throwable == null) {
			return null;
		}

		StringBuilder s = new StringBuilder();
		if (throwable.getThrowableStrRep() != null) {
			for (String str : throwable.getThrowableStrRep()) {
				s.append(str).append('\n');
			}
		}

		return s.toString();
	}

	private String composeException(IThrowableProxy throwable) {
		if (throwable == null) {
			return null;
		}

		StringBuilder s = new StringBuilder();
		s.append(throwable.getClassName()).append(": ").append(throwable.getMessage()).append('\n');

		for (StackTraceElementProxy element : throwable.getStackTraceElementProxyArray()) {
			s.append('\t').append(element.getSTEAsString()).append('\n');
		}

		IThrowableProxy cause = throwable.getCause();
		while (cause != null) {
			s.append("Caused by: ").append(cause.getClassName()).append(": ").append(cause.getMessage()).append('\n');
			for (StackTraceElementProxy element : cause.getStackTraceElementProxyArray()) {
				s.append('\t').append(element.getSTEAsString()).append('\n');
			}
			cause = cause.getCause();
		}

		return s.toString();
	}

	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		if (ois != null) {
			try {
				ois.close();
			} catch (IOException e) {
				logger.warn("Could not close connection.", e);
			} finally {
				ois = null;
			}
		}
	}

	@Override
	public String toString() {
		return this.getClass().getName() + remoteSocketAddress.toString();
	}

}
