package cn.batchfile.log.service;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javax.annotation.PostConstruct;
import javax.net.ServerSocketFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class SocketService {

	protected static final Logger logger = LoggerFactory.getLogger(SocketService.class);
	private MeterRegistry registry;

	@Value("${socket.port:4560}")
	private int port;

	@Autowired
	private StoreService storeService;

	private boolean closed = false;
	private ServerSocket serverSocket;
	private List<SocketNode> socketNodeList = new ArrayList<SocketNode>();

	private CountDownLatch latch;

	public SocketService(MeterRegistry registry) {
		this.registry = registry;
		Gauge.builder("socket.connection.active", "", s -> socketNodeList.size()).register(registry);
	}

	@PostConstruct
	public void init() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				start();
			}
		}).start();
	}

	public void start() {

		final String oldThreadName = Thread.currentThread().getName();

		try {

			final String newThreadName = getServerThreadName();
			Thread.currentThread().setName(newThreadName);

			logger.info("Listening on port " + port);
			serverSocket = getServerSocketFactory().createServerSocket(port);
			while (!closed) {
				logger.info("Waiting to accept a new client.");
				signalAlmostReadiness();
				Socket socket = serverSocket.accept();
				logger.info("Connected to client at " + socket.getInetAddress());
				logger.info("Starting new socket node.");
				SocketNode newSocketNode = new SocketNode(this, socket, storeService, registry);
				synchronized (socketNodeList) {
					socketNodeList.add(newSocketNode);
				}
				final String clientThreadName = getClientThreadName(socket);
				new Thread(newSocketNode, clientThreadName).start();
			}
		} catch (Exception e) {
			if (closed) {
				logger.info("Exception in run method for a closed server. This is normal.");
			} else {
				logger.error("Unexpected failure in run method", e);
			}
		} finally {
			Thread.currentThread().setName(oldThreadName);
		}

	}

	/**
	 * Returns the name given to the server thread.
	 */
	protected String getServerThreadName() {
		return String.format("Logback %s (port %d)", getClass().getSimpleName(), port);
	}

	/**
	 * Returns a name to identify each client thread.
	 */
	protected String getClientThreadName(Socket socket) {
		return String.format("Logback SocketNode (client: %s)", socket.getRemoteSocketAddress());
	}

	/**
	 * Gets the platform default {@link ServerSocketFactory}.
	 * <p>
	 * Subclasses may override to provide a custom server socket factory.
	 */
	protected ServerSocketFactory getServerSocketFactory() {
		return ServerSocketFactory.getDefault();
	}

	/**
	 * Signal another thread that we have established a connection This is useful
	 * for testing purposes.
	 */
	void signalAlmostReadiness() {
		if (latch != null && latch.getCount() != 0) {
			// System.out.println("signalAlmostReadiness() with latch "+latch);
			latch.countDown();
		}
	}

	/**
	 * Used for testing purposes
	 * 
	 * @param latch
	 */
	void setLatch(CountDownLatch latch) {
		this.latch = latch;
	}

	/**
	 * Used for testing purposes
	 */
	public CountDownLatch getLatch() {
		return latch;
	}

	public boolean isClosed() {
		return closed;
	}

	public void close() {
		closed = true;
		if (serverSocket != null) {
			try {
				serverSocket.close();
			} catch (IOException e) {
				logger.error("Failed to close serverSocket", e);
			} finally {
				serverSocket = null;
			}
		}

		logger.info("closing this server");
		synchronized (socketNodeList) {
			for (SocketNode sn : socketNodeList) {
				sn.close();
			}
		}
		if (socketNodeList.size() != 0) {
			logger.warn("Was expecting a 0-sized socketNodeList after server shutdown");
		}

	}

	public void socketNodeClosing(SocketNode sn) {
		logger.debug("Removing {}", sn);

		// don't allow simultaneous access to the socketNodeList
		// (e.g. removal whole iterating on the list causes
		// java.util.ConcurrentModificationException
		synchronized (socketNodeList) {
			socketNodeList.remove(sn);
		}
	}

}
