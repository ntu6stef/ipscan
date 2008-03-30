/**
 * This file is a part of Angry IP Scanner source code,
 * see http://www.azib.net/ for more information.
 * Licensed under GPLv2.
 */
package net.azib.ipscan.core;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.azib.ipscan.config.ScannerConfig;
import net.azib.ipscan.core.state.ScanningState;
import net.azib.ipscan.core.state.StateMachine;
import net.azib.ipscan.core.state.StateTransitionListener;
import net.azib.ipscan.feeders.Feeder;

/**
 * Scanning thread.
 * 
 * @author Anton Keks
 */
public class ScannerThread extends Thread implements StateTransitionListener {

	private Scanner scanner;
	private StateMachine stateMachine;
	private ScanningResultList scanningResultList;
	private Feeder feeder;
	private ScanningProgressCallback progressCallback;
	private ScanningResultsCallback resultsCallback;
	
	private volatile int runningThreads;
	private Set<IPThread> threads = Collections.synchronizedSet(new HashSet<IPThread>());
	
	private ScannerConfig config;
	
	public ScannerThread(Feeder feeder, Scanner scanner, StateMachine stateMachine, ScanningProgressCallback progressCallback, ScanningResultList scanningResults, ScannerConfig scannerConfig, ScanningResultsCallback resultsCallback) {
		super();
		setName(getClass().getSimpleName());
		this.config = scannerConfig;
		this.stateMachine = stateMachine;
		this.progressCallback = progressCallback;
		this.resultsCallback = resultsCallback;
		
		// this thread is daemon because we want JVM to terminate it
		// automatically if user closes the program (Main thread, that is)
		setDaemon(true);
		
		this.feeder = feeder;
		this.scanner = scanner;
		this.scanningResultList = scanningResults;
		try {
			this.scanningResultList.initNewScan(feeder);
		
			// initialize in the main thread in order to catch exceptions gracefully
			scanner.init();
		}
		catch (RuntimeException e) {
			stateMachine.reset();
			throw e;
		}
	}

	public void run() {
		try {
			// register this scan specific listener
			stateMachine.addTransitionListener(this);

			while(feeder.hasNext() && stateMachine.inState(ScanningState.SCANNING)) {
				try {
					// make a small delay between thread creation
					Thread.sleep(config.threadDelay);
									
					if (runningThreads >= config.maxThreads) {
						// skip this iteration until more threads can be created
						continue;
					}
					
					// retrieve the next IP address to scan
					final InetAddress address = feeder.next();
					
					// check if this is a likely broadcast address and needs to be skipped
					if (config.skipBroadcastAddresses && InetAddressUtils.isLikelyBroadcast(address)) {
						continue;
					}
	
					// now increment the number of active threads, because we are going
					// to start a new one below
					runningThreads++;
									
					// prepare results receiver for upcoming results
					ScanningResult result = scanningResultList.createResult(address);
					resultsCallback.prepareForResults(result);
					
					// notify listeners of the progress we are doing
					progressCallback.updateProgress(address, runningThreads, feeder.percentageComplete());
					
					// scan each IP in parallel, in a separate thread
					IPThread thread = new IPThread(address, result);
					threads.add(thread);
					thread.start();
				}
				catch (InterruptedException e) {
					return;
				}
			}
			
			// inform that no more addresses left
			stateMachine.stop();
			
			try {				
				// now wait for all threads, which are still running
				while (runningThreads > 0) {
					Thread.sleep(200);
					progressCallback.updateProgress(null, runningThreads, 100);
				}
			} 
			catch (InterruptedException e) {
				// just end the loop
			}
			
			scanner.cleanup();
			
			// finally, the scanning is complete
			stateMachine.complete();
		}
		finally {
			// unregister specific listener
			stateMachine.removeTransitionListener(this);
		}
	}
	
	/**
	 * Local stateMachine transition listener.
	 * Currently used to kill all running threads if user says so.
	 */
	public void transitionTo(ScanningState state) {
		if (state == ScanningState.KILLING) {
			// try to interrupt all threads if we get to killing state
			synchronized (threads) {
				for (Thread t : threads) {
					t.interrupt();
				}
			}
		}
	}
				
	/**
	 * This thread gets executed for each scanned IP address to do the actual
	 * scanning.
	 */
	class IPThread extends Thread {
		private InetAddress address;
		private ScanningResult result;
		
		IPThread(InetAddress address, ScanningResult result) {
			super();
			setName(getClass().getSimpleName() + ": " + address.getHostAddress());
			setDaemon(true);
			this.address = address;
			this.result = result;
		}

		public void run() {
			try {
				scanner.scan(address, result);
				resultsCallback.consumeResults(result);
			}
			finally {
				runningThreads--;
				threads.remove(this);
			}
		}
	}
}
