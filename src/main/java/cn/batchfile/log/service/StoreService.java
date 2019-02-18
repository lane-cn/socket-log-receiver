package cn.batchfile.log.service;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.alibaba.fastjson.JSONObject;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class StoreService {

	protected static final org.slf4j.Logger log = LoggerFactory.getLogger(StoreService.class);
	private Counter storeCounter;
	private Counter discardCounter;
	private Counter errorCounter;
	private Timer timer;

	private Map<String, Logger> loggers = new ConcurrentHashMap<String, Logger>();
	private AtomicInteger queueSize = new AtomicInteger(0);
	private ExecutorService executorService = null;
	
	@Value("${max.queue.size:1024}")
	private int maxQueueSize;
	
	@Value("${writer.threads:4}")
	private int writerThreads;
	
	@Value("${output.path}")
	private String outputPath;
	
	@Value("${store.days:30}")
	private int storeDays;
	
	public StoreService(MeterRegistry registry) {
		Gauge.builder("store.queue.size", "", s -> queueSize.get()).register(registry);
		Gauge.builder("store.logger.count", "", s -> loggers.size()).register(registry);
		storeCounter = Counter.builder("store.count").register(registry);
		discardCounter = Counter.builder("store.discard.count").register(registry);
		errorCounter = Counter.builder("store.error.count").register(registry);
		timer = Timer.builder("store.response.time").register(registry);
	}
	
	public void store(final JSONObject event) {
		// 判断处理队列是不是在积压，积压情况下抛弃一部分日志
		if (queueSize.incrementAndGet() > maxQueueSize) {
			log.debug("exceed max queue size, discard file log");
			discardCounter.increment();
			queueSize.decrementAndGet();
			return;
		}

		// 执行存储
		getExecutorService().submit(new Runnable() {
			public void run() {
				try {
					// 记录文件日志
					writeFile(event);
				} finally {
					// 执行完毕消除积压队列
					queueSize.decrementAndGet();
				}
			}
		});

	}
	
	private void writeFile(JSONObject event) {
		assert(event.size() > 0);
		try {
			String context = event.getString("context");
			if (StringUtils.isEmpty(context)) {
				context = "app";
			}
			final Logger logger = getOrCreateLogger(context);
	
			timer.record(() -> {
				logger.info(event.toString());
				storeCounter.increment();
			});
		} catch (Exception e) {
			errorCounter.increment();
		}
	}
	
	private Logger getOrCreateLogger(String name) {
		if (!loggers.containsKey(name)) {
			synchronized (loggers) {
				if (!loggers.containsKey(name)) {
					log.info("create logger for: {}", name);
					Logger logger = createLogger(name);
					loggers.put(name, logger);
				}
			}
		}
		return loggers.get(name);
	}

	@SuppressWarnings("unchecked")
	private Logger createLogger(String name) {
		File file = new File(outputPath);
		file = new File(file, name);

		LoggerContext logCtx = (LoggerContext) LoggerFactory.getILoggerFactory();

		PatternLayoutEncoder logEncoder = new PatternLayoutEncoder();
		logEncoder.setContext(logCtx);
		logEncoder.setPattern("%msg%n");
		logEncoder.start();

		@SuppressWarnings("rawtypes")
		RollingFileAppender logFileAppender = new RollingFileAppender();
		logFileAppender.setContext(logCtx);
		logFileAppender.setName(name);
		logFileAppender.setEncoder(logEncoder);
		logFileAppender.setAppend(true);
		logFileAppender.setFile(file.getAbsolutePath() + ".log");

		@SuppressWarnings("rawtypes")
		TimeBasedRollingPolicy logFilePolicy = new TimeBasedRollingPolicy();
		logFilePolicy.setContext(logCtx);
		logFilePolicy.setParent(logFileAppender);
		logFilePolicy.setFileNamePattern(file.getAbsolutePath() + "-%d{yyyy-MM-dd}.log");
		logFilePolicy.setMaxHistory(storeDays);
		logFilePolicy.start();

		logFileAppender.setRollingPolicy(logFilePolicy);
		logFileAppender.start();

		Logger log = logCtx.getLogger(name);
		log.setAdditive(false);
		log.setLevel(Level.ALL);
		log.addAppender(logFileAppender);

		return log;
	}

	private ExecutorService getExecutorService() {
		if (executorService == null) {
			synchronized (this) {
				if (executorService == null) {
					executorService = Executors.newFixedThreadPool(writerThreads);
				}
			}
		}
		return executorService;
	}

}
