/*
 * Copyright 2002-2012 SCOOP Software GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.scoopgmbh.copper.persistent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.scoopgmbh.copper.Response;
import de.scoopgmbh.copper.Workflow;
import de.scoopgmbh.copper.batcher.Batcher;
import de.scoopgmbh.copper.common.WorkflowRepository;
import de.scoopgmbh.copper.db.utility.RetryingTransaction;
import de.scoopgmbh.copper.internal.WorkflowAccessor;
import de.scoopgmbh.copper.monitoring.NullRuntimeStatisticsCollector;
import de.scoopgmbh.copper.monitoring.RuntimeStatisticsCollector;
import de.scoopgmbh.copper.monitoring.StmtStatistic;

/**
 * Abstract base implementation of the {@link ScottyDBStorageInterface} for SQL databases
 * 
 * @author austermann
 *
 */
public abstract class AbstractSqlScottyDBStorage implements ScottyDBStorageInterface {

	private static final Logger logger = LoggerFactory.getLogger(AbstractSqlScottyDBStorage.class);

	public AbstractSqlScottyDBStorage() {

	}

	private WorkflowRepository wfRepository;
	private Batcher batcher;
	private DataSource dataSource;
	private Thread enqueueThread;
	private ScheduledExecutorService scheduledExecutorService;
	private long deleteStaleResponsesIntervalMsec = 60L*60L*1000L;
	private boolean shutdown = false;
	protected String queryUpdateQueueState = getResourceAsString("/sql-query-ready-bpids.sql");
	private RuntimeStatisticsCollector runtimeStatisticsCollector = new NullRuntimeStatisticsCollector();
	private Serializer serializer = new StandardJavaSerializer();
	private boolean removeWhenFinished = true;

	private StmtStatistic dequeueStmtStatistic;
	private StmtStatistic queueDeleteStmtStatistic;
	private StmtStatistic enqueueUpdateStateStmtStatistic;
	private StmtStatistic insertStmtStatistic;
	private StmtStatistic deleteStaleResponsesStmtStatistic;

	public void setSerializer(Serializer serializer) {
		this.serializer = serializer;
	}

	public void setDataSource(DataSource dataSource) {
		this.dataSource = dataSource;
	}
	
	protected DataSource getDataSource() {
		return dataSource;
	}
	
	protected Batcher getBatcher() {
		return batcher;
	}
	
	public void setRuntimeStatisticsCollector(RuntimeStatisticsCollector runtimeStatisticsCollector) {
		this.runtimeStatisticsCollector = runtimeStatisticsCollector;
	}
	
	private void initStats() {
		dequeueStmtStatistic = new StmtStatistic("DBStorage.dequeue.fullquery",runtimeStatisticsCollector);
		queueDeleteStmtStatistic = new StmtStatistic("DBStorage.queue.delete",runtimeStatisticsCollector);
		enqueueUpdateStateStmtStatistic = new StmtStatistic("DBStorage.enqueue.updateState",runtimeStatisticsCollector);
		insertStmtStatistic = new StmtStatistic("DBStorage.insert",runtimeStatisticsCollector);
		deleteStaleResponsesStmtStatistic = new StmtStatistic("DBStorage.deleteStaleResponses",runtimeStatisticsCollector);
	}

	protected String getResourceAsString(String name) {
		try {
			BufferedReader br = new BufferedReader(new InputStreamReader(this.getClass().getResourceAsStream(name)));
			try {
				StringBuilder sb = new StringBuilder();
				String line = null;
				while ((line = br.readLine()) != null) {
					sb.append(line).append("\n");
				}
				return sb.toString();
			}
			finally {
				br.close();
			}
		}
		catch(IOException e) {
			throw new RuntimeException(e);
		}
	}

	public void setBatcher(Batcher batcher) {
		this.batcher = batcher;
	}

	/* (non-Javadoc)
	 * @see de.scoopgmbh.copper.persistent.ScottyDBStorageInterface#setWfRepository(de.scoopgmbh.copper.common.WorkflowRepository)
	 */
	public void setWfRepository(WorkflowRepository wfRepository) {
		this.wfRepository = wfRepository;
	}

	public void setDeleteStaleResponsesIntervalMsec(long deleteStaleResponsesIntervalMsec) {
		this.deleteStaleResponsesIntervalMsec = deleteStaleResponsesIntervalMsec;
	}


	/* (non-Javadoc)
	 * @see de.scoopgmbh.copper.persistent.ScottyDBStorageInterface#resumeBrokenBusinessProcesses()
	 */
	private void resumeBrokenBusinessProcesses() throws Exception {
		logger.info("resumeBrokenBusinessProcesses");
		new RetryingTransaction(dataSource) {
			@Override
			protected void execute() throws Exception {
				logger.info("Truncating queue...");
				Statement truncate = getConnection().createStatement();
				truncate.execute("TRUNCATE TABLE COP_QUEUE");
				truncate.close();
				logger.info("done!");

				logger.info("Adding all BPs in state 0 to queue...");
				PreparedStatement stmt = getConnection().prepareStatement("insert into cop_queue (ppool_id, priority, last_mod_ts, WORKFLOW_INSTANCE_ID) (select ppool_id, priority, last_mod_ts, id from COP_WORKFLOW_INSTANCE where state=0)");
				int rowcount = stmt.executeUpdate();
				stmt.close();
				logger.info("done - processed "+rowcount+" BP(s).");

				logger.info("Changing all WAITs to state 0...");
				stmt = getConnection().prepareStatement("update cop_wait set state=0 where state=1");
				rowcount = stmt.executeUpdate();
				stmt.close();
				logger.info("done - changed "+rowcount+" WAIT(s).");

			}
		}.run();

		logger.info("Adding all BPs in working state with existing response(s)...");
		int rowcount = 0;
		for (;;) {
			int x = updateQueueState(100000);
			if (x==0) break;
			rowcount+=x;
		}

		logger.info("done - processed "+rowcount+" BPs.");

	}

	/* (non-Javadoc)
	 * @see de.scoopgmbh.copper.persistent.ScottyDBStorageInterface#insert(de.scoopgmbh.copper.Workflow)
	 */
	public void insert(final Workflow<?> wf) throws Exception {
		if (logger.isTraceEnabled()) logger.trace("insert("+wf+")");
		new RetryingTransaction(dataSource) {
			@Override
			protected void execute() throws Exception {
				doInsert(wf, getConnection());
			}
		}.run();
	}
	
	private void doInsert(final Workflow<?> wf, Connection con) throws Exception {
		final Timestamp NOW = new Timestamp(System.currentTimeMillis());
		final String data = serializer.serializeWorkflow(wf);
		PreparedStatement stmtBP = con.prepareStatement("INSERT INTO COP_WORKFLOW_INSTANCE (ID,STATE,PRIORITY,LAST_MOD_TS,PPOOL_ID,DATA,CREATION_TS) VALUES (?,?,?,?,?,?,?)");
		PreparedStatement stmtQueue = con.prepareStatement("insert into cop_queue (ppool_id, priority, last_mod_ts, WORKFLOW_INSTANCE_ID) values (?,?,?,?)");
		stmtBP.setString(1, wf.getId());
		stmtBP.setInt(2, DBProcessingState.ENQUEUED.ordinal());
		stmtBP.setInt(3, wf.getPriority());
		stmtBP.setTimestamp(4,NOW);
		stmtBP.setString(5, wf.getProcessorPoolId());
		stmtBP.setString(6, data);
		stmtBP.setTimestamp(7, new Timestamp(wf.getCreationTS().getTime()));
		stmtBP.execute();

		stmtQueue.setString(1, wf.getProcessorPoolId());
		stmtQueue.setInt(2, wf.getPriority());
		stmtQueue.setTimestamp(3,NOW);
		stmtQueue.setString(4, wf.getId());
		stmtQueue.execute();
	}
	
	private void doInsert(final List<Workflow<?>> wfs, Connection con) throws Exception {
		final Timestamp NOW = new Timestamp(System.currentTimeMillis());
		PreparedStatement stmtBP = con.prepareStatement("INSERT INTO COP_WORKFLOW_INSTANCE (ID,STATE,PRIORITY,LAST_MOD_TS,PPOOL_ID,DATA,CREATION_TS) VALUES (?,?,?,?,?,?,?)");
		PreparedStatement stmtQueue = con.prepareStatement("insert into cop_queue (ppool_id, priority, last_mod_ts, WORKFLOW_INSTANCE_ID) values (?,?,?,?)");
		int n = 0;
		for (int i=0; i<wfs.size(); i++) {
			Workflow<?> wf = wfs.get(i);
			final String data = serializer.serializeWorkflow(wf);
			stmtBP.setString(1, wf.getId());
			stmtBP.setInt(2, DBProcessingState.ENQUEUED.ordinal());
			stmtBP.setInt(3, wf.getPriority());
			stmtBP.setTimestamp(4,NOW);
			stmtBP.setString(5, wf.getProcessorPoolId());
			stmtBP.setString(6, data);
			stmtBP.setTimestamp(7, new Timestamp(wf.getCreationTS().getTime()));
			stmtBP.addBatch();

			stmtQueue.setString(1, wf.getProcessorPoolId());
			stmtQueue.setInt(2, wf.getPriority());
			stmtQueue.setTimestamp(3,NOW);
			stmtQueue.setString(4, wf.getId());
			stmtQueue.addBatch();

			n++;
			if (i % 100 == 0 || (i+1) == wfs.size()) {
				insertStmtStatistic.start();
				stmtBP.executeBatch();
				stmtQueue.executeBatch();
				insertStmtStatistic.stop(n);
				n = 0;
			}
		}
	}

	/* (non-Javadoc)
	 * @see de.scoopgmbh.copper.persistent.ScottyDBStorageInterface#insert(java.util.List)
	 */
	public void insert(final List<Workflow<?>> wfs) throws Exception {
		if (logger.isTraceEnabled()) logger.trace("insert("+wfs.size()+")");
		new RetryingTransaction(dataSource) {
			@Override
			protected void execute() throws Exception {
				doInsert(wfs, getConnection());
			}
		}.run();
	}

	/* (non-Javadoc)
	 * @see de.scoopgmbh.copper.persistent.ScottyDBStorageInterface#finish(de.scoopgmbh.copper.Workflow)
	 */
	public void finish(final Workflow<?> w) {
		if (logger.isTraceEnabled()) logger.trace("finish("+w.getId()+")");
		final PersistentWorkflow<?> pwf = (PersistentWorkflow<?>) w;
		batcher.submitBatchCommand(new SqlRemove.Command(pwf,dataSource,removeWhenFinished));
	}

	/* (non-Javadoc)
	 * @see de.scoopgmbh.copper.persistent.ScottyDBStorageInterface#dequeue(java.lang.String, int)
	 */
	public List<Workflow<?>> dequeue(final String ppoolId, final int max) throws Exception {
		if (logger.isTraceEnabled()) logger.trace("dequeue("+ppoolId+", "+max+")");

		final long startTS = System.currentTimeMillis();

		final List<Workflow<?>> rv = new ArrayList<Workflow<?>>(max);
		new RetryingTransaction(dataSource) {
			@Override
			protected void execute() throws Exception {
				final List<String> invalidBPs = new ArrayList<String>();
				final PreparedStatement dequeueStmt = createDequeueStmt(getConnection(), ppoolId, max);

				final PreparedStatement deleteStmt = getConnection().prepareStatement("delete from cop_queue where WORKFLOW_INSTANCE_ID=?");
				dequeueStmtStatistic.start();
				final ResultSet rs = dequeueStmt.executeQuery();
				final Map<String,Workflow<?>> map = new HashMap<String, Workflow<?>>(max*3);
				while (rs.next()) {
					final String id = rs.getString(1);
					final int prio = rs.getInt(2);

					deleteStmt.setString(1, id);
					deleteStmt.addBatch();

					try {
						PersistentWorkflow<?> wf = (PersistentWorkflow<?>) serializer.deserializeWorkflow(rs.getString(3), wfRepository);
						wf.setId(id);
						wf.setProcessorPoolId(ppoolId);
						wf.setPriority(prio);
						WorkflowAccessor.setCreationTS(wf, new Date(rs.getTimestamp(4).getTime()));
						map.put(wf.getId(), wf);
					}
					catch(Exception e) {
						logger.error("decoding of '"+id+"' failed: "+e.toString(),e);
						invalidBPs.add(id);
					}
				}
				rs.close();
				dequeueStmt.close();
				dequeueStmtStatistic.stop(map.size());

				if (map.isEmpty()) {
					return;
				}

				PreparedStatement stmt = getConnection().prepareStatement("select w.WORKFLOW_INSTANCE_ID, w.correlation_id, r.response from (select WORKFLOW_INSTANCE_ID, correlation_id from cop_wait where WORKFLOW_INSTANCE_ID in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)) w LEFT OUTER JOIN cop_response r ON w.correlation_id = r.correlation_id");
				List<List<String>> ids = splitt(map.keySet(),25); 
				for (List<String> id : ids) {
					stmt.clearParameters();
					for (int i=0; i<25; i++) {
						stmt.setString(i+1, id.size() >= i+1 ? id.get(i) : null);
					}
					ResultSet rsResponses = stmt.executeQuery();
					while (rsResponses.next()) {
						String bpId = rsResponses.getString(1);
						String cid = rsResponses.getString(2);
						String response = rsResponses.getString(3);
						PersistentWorkflow<?> wf = (PersistentWorkflow<?>) map.get(bpId);
						Response<?> r;
						if (response != null) {
							r = (Response<?>) serializer.deserializeResponse(response);
						}
						else {
							// timeout
							r = new Response<Object>(cid);
						}
						wf.putResponse(r);
						if (wf.cidList == null) wf.cidList = new ArrayList<String>();
						wf.cidList.add(cid);
					}
					rsResponses.close();
				}

				queueDeleteStmtStatistic.start();
				deleteStmt.executeBatch();
				queueDeleteStmtStatistic.stop(map.size());

				rv.addAll(map.values());

				if (!invalidBPs.isEmpty()) {
					PreparedStatement updateBpStmt = getConnection().prepareStatement("update COP_WORKFLOW_INSTANCE set state=? where id=?");
					for (String id : invalidBPs) {
						updateBpStmt.setInt(1, DBProcessingState.INVALID.ordinal());
						updateBpStmt.setString(2,id);
						updateBpStmt.addBatch();
					}
					updateBpStmt.executeBatch();
					// ggf. auch die Waits und Responses löschen
				}
			}
		}.run();
		if (logger.isTraceEnabled()) logger.trace("dequeue for pool "+ppoolId+" returns "+rv.size()+" element(s)");

		if (logger.isDebugEnabled()) logger.debug(rv.size()+" in "+(System.currentTimeMillis()-startTS));
		return rv;
	}

	private int updateQueueState(final int max) {
		final int[] rowcount = { 0 };
		final long startTS = System.currentTimeMillis();
		try {
			new RetryingTransaction(dataSource) {
				@Override
				protected void execute() throws Exception {
					final Timestamp NOW = new Timestamp(System.currentTimeMillis());
					enqueueUpdateStateStmtStatistic.start();
					
					PreparedStatement queryStmt = createUpdateStateStmt(getConnection(), max);
					ResultSet rs = queryStmt.executeQuery();
					PreparedStatement updStmt = getConnection().prepareStatement("update cop_wait set state=1 where WORKFLOW_INSTANCE_ID=?");
					PreparedStatement insStmt = getConnection().prepareStatement("INSERT INTO COP_QUEUE (PPOOL_ID, PRIORITY, LAST_MOD_TS, WORKFLOW_INSTANCE_ID) VALUES (?,?,?,?)");
					while (rs.next()) {
						rowcount[0]++;
						
						final String bpId = rs.getString(1);
						final String ppoolId = rs.getString(2);
						final int prio = rs.getInt(3);
						
						updStmt.setString(1, bpId);
						updStmt.addBatch();
						
						insStmt.setString(1, ppoolId);
						insStmt.setInt(2, prio);
						insStmt.setTimestamp(3, NOW);
						insStmt.setString(4, bpId);
						insStmt.addBatch();
					}
					if (rowcount[0] > 0) {
						insStmt.executeBatch();
						updStmt.executeBatch();
					}
					enqueueUpdateStateStmtStatistic.stop(rowcount[0] == 0 ? 1 : rowcount[0]);
				}
			}.run();
		} 
		catch (Exception e) {
			logger.error("",e);
		}
		logger.info("Queue update in "+(System.currentTimeMillis()-startTS)+" msec");
		return rowcount[0];
	}

	protected List<List<String>> splitt(Collection<String> keySet, int n) {
		if (keySet.isEmpty()) 
			return Collections.emptyList();

		List<List<String>> r = new ArrayList<List<String>>(keySet.size()/n+1);
		List<String> l = new ArrayList<String>(n);
		for (String s : keySet) {
			l.add(s);
			if (l.size() == n) {
				r.add(l);
				l = new ArrayList<String>(n);
			}
		}
		if (l.size() > 0) {
			r.add(l);
		}
		return r;
	}

	/* (non-Javadoc)
	 * @see de.scoopgmbh.copper.persistent.ScottyDBStorageInterface#notify(de.scoopgmbh.copper.Response, java.lang.Object)
	 */
	public void notify(final Response<?> response, final Object callback) throws Exception {
		if (logger.isTraceEnabled()) logger.trace("notify("+response+")");
		if (response == null)
			throw new NullPointerException();
		batcher.submitBatchCommand(new SqlNotify.Command(response, dataSource, serializer));
	}

	/* (non-Javadoc)
	 * @see de.scoopgmbh.copper.persistent.ScottyDBStorageInterface#notify(java.util.List)
	 */
	public void notify(final List<Response<?>> response) throws Exception {
		for (Response<?> r : response)
			notify(r,null);
	}

	/* (non-Javadoc)
	 * @see de.scoopgmbh.copper.persistent.ScottyDBStorageInterface#registerCallback(de.scoopgmbh.copper.persistent.RegisterCall)
	 */
	public void registerCallback(final RegisterCall rc) throws Exception {
		if (logger.isTraceEnabled()) logger.trace("registerCallback("+rc+")");
		if (rc == null) 
			throw new NullPointerException();
		batcher.submitBatchCommand(new SqlRegisterCallback.Command(rc, dataSource, serializer));
	}


	/* (non-Javadoc)
	 * @see de.scoopgmbh.copper.persistent.ScottyDBStorageInterface#startup()
	 */
	public synchronized void startup() {
		try {
			initStats();
			
			deleteStaleResponse();
			resumeBrokenBusinessProcesses();
			enqueueThread = new Thread("ENQUEUE") {
				@Override
				public void run() {
					updateQueueState();
				}
			};
			enqueueThread.start();

			scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

			scheduledExecutorService.scheduleWithFixedDelay(new Runnable() {
				@Override
				public void run() {
					try {
						deleteStaleResponse();
					} 
					catch (Exception e) {
						logger.error("deleteStaleResponse failed",e);
					}
				}
			}, deleteStaleResponsesIntervalMsec, deleteStaleResponsesIntervalMsec, TimeUnit.MILLISECONDS);
		}
		catch(Exception e) {
			throw new Error("Unable to startup",e);
		}
	}

	/* (non-Javadoc)
	 * @see de.scoopgmbh.copper.persistent.ScottyDBStorageInterface#shutdown()
	 */
	public synchronized void shutdown() {
		if (shutdown)
			return;

		shutdown = true;
		enqueueThread.interrupt();
		scheduledExecutorService.shutdown();
	}

	/* (non-Javadoc)
	 * @see de.scoopgmbh.copper.persistent.ScottyDBStorageInterface#deleteStaleResponse(java.sql.Timestamp)
	 */
	private void deleteStaleResponse() throws Exception {
		if (logger.isTraceEnabled()) logger.trace("deleteStaleResponse()");

		final int[] n = { 0 };
		final int MAX_ROWS = 20000;
		do {
			new RetryingTransaction(dataSource) {
				@Override
				protected void execute() throws Exception {
					PreparedStatement stmt = createDeleteStaleResponsesStmt(getConnection(), MAX_ROWS);
					deleteStaleResponsesStmtStatistic.start();
					n[0] = stmt.executeUpdate();
					deleteStaleResponsesStmtStatistic.stop(n[0]);
					if (logger.isTraceEnabled()) logger.trace("deleted "+n+" stale response(s).");
				}
			}.run();
		}
		while(n[0] == MAX_ROWS);
	}

	private void updateQueueState() {
		final int max = 5000;
		logger.info("started");
		while(!shutdown) {
			int x=0;
			try {
				x = updateQueueState(max);
			}
			catch(Exception e) {
				logger.error("updateQueueState failed",e);
			}
			if (x == 0) {
				try {
					Thread.sleep(2000);
				} 
				catch (InterruptedException e) {
					//ignore
				}
			}
			else if (x < max) {
				try {
					Thread.sleep(1000);
				} 
				catch (InterruptedException e) {
					//ignore
				}
			}
		}
		logger.info("finished");
	}

	@Override
	public void insert(Workflow<?> wf, Connection con) throws Exception {
		if (con == null) {
			insert(wf);
		}
		else {
			doInsert(wf, con);
		}
	}

	@Override
	public void insert(List<Workflow<?>> wfs, Connection con) throws Exception {
		if (con == null) {
			insert(wfs);
		}
		else {
			doInsert(wfs, con);
		}
	}

	@Override
	public void restart(final String workflowInstanceId) throws Exception {
		logger.trace("restart("+workflowInstanceId+")");
		new RetryingTransaction(dataSource) {
			@Override
			protected void execute() throws Exception {
				final Timestamp NOW = new Timestamp(System.currentTimeMillis());
				PreparedStatement stmtQueue = getConnection().prepareStatement("INSERT INTO COP_QUEUE (PPOOL_ID, PRIORITY, LAST_MOD_TS, WORKFLOW_INSTANCE_ID) (SELECT PPOOL_ID, PRIORITY, ?, ID FROM COP_WORKFLOW_INSTANCE WHERE ID=? AND STATE=5)");
				PreparedStatement stmtInstance = getConnection().prepareStatement("UPDATE COP_WORKFLOW_INSTANCE SET STATE=?, LAST_MOD_TS=? WHERE ID=? AND (STATE=? OR STATE=?)");
				stmtQueue.setTimestamp(1, NOW);
				stmtQueue.setString(2, workflowInstanceId);
				stmtInstance.setInt(1, DBProcessingState.ENQUEUED.ordinal());
				stmtInstance.setTimestamp(2, NOW);
				stmtInstance.setString(3, workflowInstanceId);
				stmtInstance.setInt(4, DBProcessingState.ERROR.ordinal());
				stmtInstance.setInt(5, DBProcessingState.INVALID.ordinal());
				stmtQueue.execute();
				stmtInstance.execute();
			}
		}.run();
		logger.info(workflowInstanceId+" successfully queued for restart.");
	}

	@Override
	public void setRemoveWhenFinished(boolean removeWhenFinished) {
		this.removeWhenFinished = removeWhenFinished;
	}

	@Override
	public void restartAll() {
		throw new UnsupportedOperationException();
	}

	protected abstract PreparedStatement createUpdateStateStmt(final Connection c, final int max) throws SQLException;

	protected abstract PreparedStatement createDequeueStmt(final Connection c, final String ppoolId, final int max) throws SQLException;

	protected abstract PreparedStatement createDeleteStaleResponsesStmt(final Connection c, final int MAX_ROWS) throws SQLException;

}
