package io.fluo.impl;

import io.fluo.impl.TransactorID.TrStatus;
import io.fluo.impl.TransactorCache.TcStatus;

import java.io.Closeable;
import java.io.IOException;

import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.ConditionalWriter;
import org.apache.accumulo.core.client.ConditionalWriterConfig;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

/** Shared Fluo resources that must be closed
 */
public class SharedResources implements Closeable {

  private Configuration config;
  private BatchWriter bw;
  private ConditionalWriter cw;
  private SharedBatchWriter sbw;
  private CuratorFramework curator;
  private TransactorID tid = null;
  private TransactorCache cache = null;
  private volatile boolean isClosed = false;
  
  public SharedResources(Configuration config) throws TableNotFoundException {
    this.config = config;
    curator = CuratorFrameworkFactory.newClient(config.getConnector().getInstance().getZooKeepers(), 
        new ExponentialBackoffRetry(1000, 10));
    curator.start();
    bw = config.getConnector().createBatchWriter(config.getTable(), new BatchWriterConfig());
    sbw = new SharedBatchWriter(bw);
    cw = config.getConnector().createConditionalWriter(config.getTable(), 
        new ConditionalWriterConfig().setAuthorizations(config.getAuthorizations()));
  }
  
  public SharedBatchWriter getBatchWriter() {
    checkIfClosed();
    return sbw;
  }
  
  public ConditionalWriter getConditionalWriter() {
    checkIfClosed();
    return cw;
  }
  
  public CuratorFramework getCurator() {
    checkIfClosed();
    return curator;
  }
  
  public synchronized TransactorID getTransactorID() {
    checkIfClosed();
    if (tid == null) {
      tid = new TransactorID(config);
    } else if (tid.getStatus() == TrStatus.CLOSED) {
      throw new IllegalStateException("Transactor is closed!");
    }
    return tid;
  }
  
  public synchronized TransactorCache getTransactorCache() {
    checkIfClosed();
    if (cache == null) {
      cache = new TransactorCache(config);
    } else if (cache.getStatus() == TcStatus.CLOSED) {
      throw new IllegalStateException("TransactorCache is closed!");
    }
    return cache;
  }
  
  @Override
  public synchronized void close() {
    isClosed = true;
    if (tid != null) {
      try {
        tid.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    if (cache != null) {
      try {
        cache.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    cw.close();
    sbw.close();
    try {
      bw.close();
    } catch (MutationsRejectedException e) {
      throw new RuntimeException(e);
    }
    curator.close();
  }
  
  private void checkIfClosed() {
    if (isClosed) {
      throw new IllegalStateException("SharedResources is closed!");
    }
  }
}