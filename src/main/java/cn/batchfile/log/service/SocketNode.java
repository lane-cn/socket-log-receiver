package cn.batchfile.log.service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.alibaba.fastjson.JSONObject;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

public class SocketNode implements Runnable {

	protected static final Logger logger = LoggerFactory.getLogger(SocketNode.class);
	
	Socket socket;
	ObjectInputStream ois;
	SocketAddress remoteSocketAddress;

	boolean closed = false;
	SocketService socketService;
	StoreService storeService;

	public SocketNode(SocketService socketService, Socket socket, StoreService storeService) {
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

		ILoggingEvent event;
		// Logger remoteLogger;

		try {
			while (!closed) {
				// read an event from the wire
				event = (ILoggingEvent) ois.readObject();

				// compose log object
				JSONObject obj = composeLogObject(event);
				//logger.info(obj.toString());

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
		
		// properties
		String contextName = event.getLoggerContextVO().getName();
		obj.put("timestamp", event.getTimeStamp());
		obj.put("message", event.getFormattedMessage());
		if (remoteSocketAddress instanceof InetSocketAddress) {
			obj.put("host", ((InetSocketAddress)remoteSocketAddress).getHostString());
			obj.put("port", String.valueOf(((InetSocketAddress)remoteSocketAddress).getPort()));
		}
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
