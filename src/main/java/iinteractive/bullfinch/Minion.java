package iinteractive.bullfinch;

import java.io.IOException;
import java.io.StringReader;
import java.util.Iterator;
import java.util.concurrent.TimeoutException;

import net.rubyeye.xmemcached.MemcachedClient;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A minion is a threadified instance of a Worker that knows how to talk to the
 * kestrel queue.
 *
 * @author gphat
 *
 */
public class Minion implements Runnable {

	static Logger logger = LoggerFactory.getLogger(Minion.class);
	private PerformanceCollector collector;
	private String queueName;
	private Worker worker;
	private MemcachedClient kestrel;
	private int timeout;
	private JSONParser parser;

	private volatile boolean cancelled = false;

	public void setTimeout(int timeout) {

		this.timeout = timeout;
	}

	/**
	 * Create a new minion.
	 *
	 * @param client  Pre-connected Kestrel client
	 * @param queueName Name of the queue to talk to
	 * @param worker The worker instance we're wrapping
	 * @param timeout The timeout for waiting on the queue
	 */
	public Minion(PerformanceCollector collector, MemcachedClient client, String queueName, Worker worker, int timeout) {

		this.collector = collector;
		this.queueName = queueName;
		this.kestrel = client;
		this.worker = worker;
		this.timeout = timeout;

		this.parser = new JSONParser();
	}

	/**
	 * Run the thread.  This method will call a get() on the queue, waiting on
	 * the timeout.  When it gets a message it will pass it off to the worker
	 * to handle.
	 */
	@SuppressWarnings("unchecked")
	public void run() {

		logger.debug("Began minion");

		try {
			this.loop();
		} catch (Exception e) {
			logger.error("Error in worker thread, exiting", e);
			return;
		}
	}

	private void loop() throws Exception {
		while(!Thread.currentThread().isInterrupted() && !cancelled) {
			try {
				logger.debug("Opening item from queue");
				// We're adding 1000 (1 second) to the queue timeout to let
				// xmemcached have some breathing room. Kestrel will timeout
				// by itself.
				String val = this.kestrel.get(this.queueName + "/t=" + this.timeout + "/open", this.timeout);

				if (val != null) {
					try {
						process(val);
					} catch (ProcessTimeoutException e) {
						// ignore a timeout exception
					}

					// confirm the item we took off the queue.
					logger.debug("Closing item from queue");
					this.kestrel.get(this.queueName + "/close");
				}
			} catch (IOException e) {
				logger.error("Error in worker thread!", e);
			} catch (TimeoutException e) {
				logger.debug("Timeout expired, cycling");
			}
		}
	}

	private void process(String val) throws Exception {
		JSONObject request = null;

		logger.debug("Got item from queue:\n" + val);

		try {
			request = (JSONObject) parser.parse(new StringReader(val));
		} catch (Error e) {
			logger.debug("unable to parse input, ignoring");
			return;
		} catch (Exception e) {
			logger.debug("unable to parse input, ignoring");
			return;
		}

		// Try and get the response queue.
		String responseQueue = (String) request.get("response_queue");
		if(responseQueue == null) {
			logger.debug("request did not contain a response queue");
			return;
		}
		logger.debug("Response will go to " + responseQueue);

		// Get a list of items back from the worker
		Iterator<String> items = this.worker.handle(collector, request);
		// Send those items back into the queue

		long start = System.currentTimeMillis();
		while(items.hasNext()) {
			this.kestrel.set(responseQueue, 0, items.next());
		}
		collector.add(
			"ResultSet iteration and queue insertion",
			System.currentTimeMillis() - start,
			(String) request.get("tracer")
		);
		// Top if off with an EOF.
		this.kestrel.set(responseQueue, 0, "{ \"EOF\":\"EOF\" }");
	}

	public void cancel() {

		logger.info("Cancel requested, will exit soon.");
		this.cancelled = true;
	}
}
