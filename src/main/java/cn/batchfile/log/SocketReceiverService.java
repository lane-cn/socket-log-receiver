package cn.batchfile.log;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SocketReceiverService {

	private static final Logger LOG = LoggerFactory.getLogger(SocketReceiverService.class);
	
	@PostConstruct
	public void init() {
		LOG.info("socket receiver start");
	}
}
