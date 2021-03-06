/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package herddb.cluster;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerEntry;
import org.apache.bookkeeper.client.LedgerHandle;

import herddb.log.CommitLog;
import herddb.log.CommitLogResult;
import herddb.log.FullRecoveryNeededException;
import herddb.log.LogEntry;
import herddb.log.LogNotAvailableException;
import herddb.log.LogSequenceNumber;
import herddb.utils.EnsureLongIncrementAccumulator;

/**
 * Commit log replicated on Apache Bookkeeper
 *
 * @author enrico.olivelli
 */
public class BookkeeperCommitLog extends CommitLog {

    private static final Logger LOGGER = Logger.getLogger(BookkeeperCommitLog.class.getName());

    private final String sharedSecret = "herddb";
    private final BookKeeper bookKeeper;
    private final ZookeeperMetadataStorageManager metadataManager;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final String tableSpaceUUID;
    private volatile CommitFileWriter writer;
    private long currentLedgerId = 0;
    private long lastLedgerId = -1;
    private final AtomicLong lastSequenceNumber = new AtomicLong(-1);
    private LedgersInfo actualLedgersList;
    private int ensemble = 1;
    private int writeQuorumSize = 1;
    private int ackQuorumSize = 1;
    private long ledgersRetentionPeriod = 1000 * 60 * 60 * 24;
    private volatile boolean closed = false;
    private volatile boolean failed = false;

    public LedgersInfo getActualLedgersList() {
        return actualLedgersList;
    }

    private void signalLogFailed() {
        failed = true;
    }

    private class CommitFileWriter implements AutoCloseable {

        private volatile LedgerHandle out;
        private final long ledgerId;
        private volatile boolean errorOccurredDuringWrite;

        private CommitFileWriter() throws LogNotAvailableException {
            try {
                this.out = bookKeeper.createLedger(ensemble, writeQuorumSize, ackQuorumSize,
                    BookKeeper.DigestType.CRC32, sharedSecret.getBytes(StandardCharsets.UTF_8));
                this.ledgerId = this.out.getId();
            } catch (InterruptedException | BKException err) {
                throw new LogNotAvailableException(err);
            }
        }

        public long getLedgerId() {
            return ledgerId;
        }

        public CompletableFuture<LogSequenceNumber> writeEntry(LogEntry edit) {
            byte[] serialize = edit.serialize();
            CompletableFuture<LogSequenceNumber> res = new CompletableFuture<>();
            this.out.asyncAddEntry(serialize, (int rc, LedgerHandle lh, long offset, Object o) -> {
                if (rc == BKException.Code.OK) {
                    res.complete(new LogSequenceNumber(lh.getId(), offset));
                } else {
                    errorOccurredDuringWrite = true;
                    res.completeExceptionally(BKException.create(rc));
                }
            }, null);
            lastLedgerId = ledgerId;
            return res;
        }

        @Override
        public void close() throws LogNotAvailableException {
            LedgerHandle _out = out;
            if (_out == null) {
                return;
            }
            try {
                LOGGER.log(Level.SEVERE, "Closing ledger " + _out.getId()
                    + ", with LastAddConfirmed=" + _out.getLastAddConfirmed()
                    + ", LastAddPushed=" + _out.getLastAddPushed()
                    + " length=" + _out.getLength()
                    + ", errorOccurred:" + errorOccurredDuringWrite);

                _out.close();
            } catch (InterruptedException | BKException err) {
                throw new LogNotAvailableException(err);
            } finally {
                out = null;
            }
        }

    }

    public BookkeeperCommitLog(String tableSpace, ZookeeperMetadataStorageManager metadataStorageManager, BookKeeper bookkeeper) throws LogNotAvailableException {
        this.metadataManager = metadataStorageManager;
        this.tableSpaceUUID = tableSpace;
        this.bookKeeper = bookkeeper;
    }

    public int getEnsemble() {
        return ensemble;
    }

    public void setEnsemble(int ensemble) {
        this.ensemble = ensemble;
    }

    public int getWriteQuorumSize() {
        return writeQuorumSize;
    }

    public void setWriteQuorumSize(int writeQuorumSize) {
        this.writeQuorumSize = writeQuorumSize;
    }

    public int getAckQuorumSize() {
        return ackQuorumSize;
    }

    public void setAckQuorumSize(int ackQuorumSize) {
        this.ackQuorumSize = ackQuorumSize;
    }

    public long getLedgersRetentionPeriod() {
        return ledgersRetentionPeriod;
    }

    public void setLedgersRetentionPeriod(long ledgersRetentionPeriod) {
        this.ledgersRetentionPeriod = ledgersRetentionPeriod;
    }

    @Override
    public CommitLogResult log(LogEntry edit, boolean synch) throws LogNotAvailableException {
        if (isHasListeners()) {
            synch = true;
        }
        CommitFileWriter _writer = writer;
        CompletableFuture<LogSequenceNumber> res;
        if (closed || _writer == null) {
            res = new CompletableFuture();
            res.completeExceptionally(
                new LogNotAvailableException(new Exception("this commitlog has been closed"))
                    .fillInStackTrace());
        } else {
            res = _writer.writeEntry(edit);
            res.handleAsync((pos, error) -> {
                if (error != null) {
                    handleBookKeeperAsyncFailure(error, edit);
                } else if (pos != null) {
                    if (lastLedgerId == pos.ledgerId) {
                        lastSequenceNumber.accumulateAndGet(pos.offset,
                            EnsureLongIncrementAccumulator.INSTANCE);
                    }
                }
                return null;
            }
            );
        }

        if (synch) {
            try {
                LogSequenceNumber logPos = res.get();
                if (lastLedgerId == logPos.ledgerId) {
                    lastSequenceNumber.accumulateAndGet(logPos.offset,
                        EnsureLongIncrementAccumulator.INSTANCE);
                }
                notifyListeners(logPos, edit);
            } catch (ExecutionException errorOnSynch) {
                Throwable cause = errorOnSynch.getCause();
                if (cause instanceof LogNotAvailableException) {
                    throw (LogNotAvailableException) cause;
                } else {
                    throw new LogNotAvailableException(cause);
                }
            } catch (InterruptedException cause) {
                LOGGER.log(Level.SEVERE, "bookkeeper client interrupted", cause);
                throw new LogNotAvailableException(cause);
            }
        }
        return new CommitLogResult(res, !synch);
    }

    private void handleBookKeeperAsyncFailure(Throwable cause, LogEntry edit) {

        LOGGER.log(Level.SEVERE, "bookkeeper async failure on tablespace " + this.tableSpaceUUID + " while writing entry " + edit, cause);
        if (cause instanceof BKException.BKLedgerClosedException) {
            LOGGER.log(Level.SEVERE, "ledger has been closed, need to open a new ledger", closed);
        } else if (cause instanceof BKException.BKLedgerFencedException) {
            LOGGER.log(Level.SEVERE, "this server was fenced!", cause);
            close();
            signalLogFailed();
        } else if (cause instanceof BKException.BKNotEnoughBookiesException) {
            LOGGER.log(Level.SEVERE, "bookkeeper failure", cause);
            close();
            signalLogFailed();
        }
    }

    private void openNewLedger() throws LogNotAvailableException {
        lock.writeLock().lock();
        try {
            closeCurrentWriter();
            writer = new CommitFileWriter();
            currentLedgerId = writer.getLedgerId();
            LOGGER.log(Level.SEVERE, "Opened new ledger:" + currentLedgerId);
            if (actualLedgersList.getFirstLedger() < 0) {
                actualLedgersList.setFirstLedger(currentLedgerId);
            }
            actualLedgersList.addLedger(currentLedgerId);
            metadataManager.saveActualLedgersList(tableSpaceUUID, actualLedgersList);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void recovery(LogSequenceNumber snapshotSequenceNumber, BiConsumer<LogSequenceNumber, LogEntry> consumer, boolean fencing) throws LogNotAvailableException {
        this.actualLedgersList = metadataManager.getActualLedgersList(tableSpaceUUID);
        LOGGER.log(Level.SEVERE, "Actual ledgers list:" + actualLedgersList + " tableSpace " + tableSpaceUUID);
        this.lastLedgerId = snapshotSequenceNumber.ledgerId;
        this.currentLedgerId = snapshotSequenceNumber.ledgerId;
        this.lastSequenceNumber.set(snapshotSequenceNumber.offset);
        LOGGER.log(Level.SEVERE, "recovery from latest snapshotSequenceNumber:" + snapshotSequenceNumber);
        if (currentLedgerId > 0 && !this.actualLedgersList.getActiveLedgers().contains(currentLedgerId) && !this.actualLedgersList.getActiveLedgers().isEmpty()) {
            // TODO: download snapshot from another remote broker
            throw new FullRecoveryNeededException(new Exception("Actual ledgers list does not include latest snapshot ledgerid:" + currentLedgerId));
        }
        if (snapshotSequenceNumber.isStartOfTime() && !this.actualLedgersList.getActiveLedgers().isEmpty() && !this.actualLedgersList.getActiveLedgers().contains(this.actualLedgersList.getFirstLedger())) {
            throw new FullRecoveryNeededException(new Exception("Local data is absent, and actual ledger list " + this.actualLedgersList.getActiveLedgers() + " does not contain first ledger of ever: " + this.actualLedgersList.getFirstLedger()));
        }
        try {
            for (long ledgerId : actualLedgersList.getActiveLedgers()) {

                if (ledgerId < snapshotSequenceNumber.ledgerId) {
                    LOGGER.log(Level.SEVERE, "Skipping ledger " + ledgerId);
                    continue;
                }
                LedgerHandle handle;
                if (fencing) {
                    handle = bookKeeper.openLedger(ledgerId, BookKeeper.DigestType.CRC32, sharedSecret.getBytes(StandardCharsets.UTF_8));
                } else {
                    handle = bookKeeper.openLedgerNoRecovery(ledgerId, BookKeeper.DigestType.CRC32, sharedSecret.getBytes(StandardCharsets.UTF_8));
                }
                try {
                    long first;
                    if (ledgerId == snapshotSequenceNumber.ledgerId) {
                        first = snapshotSequenceNumber.offset;
                        LOGGER.log(Level.SEVERE, "Recovering from latest snapshot ledger " + ledgerId + ", starting from entry " + first);
                    } else {
                        first = 0;
                        LOGGER.log(Level.SEVERE, "Recovering from ledger " + ledgerId + ", starting from entry " + first);
                    }
                    long lastAddConfirmed = handle.getLastAddConfirmed();
                    LOGGER.log(Level.SEVERE, "Recovering from ledger " + ledgerId + ", first=" + first + " lastAddConfirmed=" + lastAddConfirmed);

                    final int BATCH_SIZE = 10000;
                    if (lastAddConfirmed >= 0) {

                        for (long b = first; b <= lastAddConfirmed;) {
                            long start = b;
                            long end = b + BATCH_SIZE;
                            if (end > lastAddConfirmed) {
                                end = lastAddConfirmed;
                            }
                            b = end + 1;
                            double percent = ((start - first) * 100.0 / (lastAddConfirmed + 1));
                            int entriesToRead = (int) (1 + end - start);
                            LOGGER.log(Level.SEVERE, "From entry {0}, to entry {1} ({2} %)", new Object[]{start, end, percent});
                            long _start = System.currentTimeMillis();

                            Enumeration<LedgerEntry> entries = handle.readEntries(start, end);
                            int localEntryCount = 0;
                            while (entries.hasMoreElements()) {

                                LedgerEntry entry = entries.nextElement();
                                long entryId = entry.getEntryId();
                                LogSequenceNumber number = new LogSequenceNumber(ledgerId, entryId);
                                LogEntry statusEdit = LogEntry.deserialize(entry.getEntry());
                                lastLedgerId = ledgerId;
                                currentLedgerId = ledgerId;
                                lastSequenceNumber.set(entryId);
                                if (number.after(snapshotSequenceNumber)) {
                                    LOGGER.log(Level.FINEST, "RECOVER ENTRY #" + localEntryCount + " {0}, {1}", new Object[]{number, statusEdit});
                                    consumer.accept(number, statusEdit);
                                } else {
                                    LOGGER.log(Level.FINEST, "SKIP ENTRY #" + localEntryCount + " {0}<{1}, {2}", new Object[]{number, snapshotSequenceNumber, statusEdit});
                                }
                                localEntryCount++;
                            }
                            LOGGER.log(Level.SEVERE, "read " + localEntryCount + " entries from ledger " + ledgerId + ", expected " + entriesToRead);

                            LOGGER.log(Level.SEVERE, "finished waiting for " + entriesToRead + " entries to be read from ledger " + ledgerId);
                            if (localEntryCount != entriesToRead) {
                                throw new LogNotAvailableException("Read " + localEntryCount + " entries, expected " + entriesToRead);
                            }
                            lastLedgerId = ledgerId;
                            lastSequenceNumber.set(end);
                            long _stop = System.currentTimeMillis();
                            LOGGER.log(Level.SEVERE, "From entry {0}, to entry {1} ({2} %) read time {3}", new Object[]{start, end, percent, (_stop - _start) + " ms"});
                        }
                    }
                } finally {
                    handle.close();
                }
            }
            LOGGER.severe("After recovery of " + tableSpaceUUID + " lastSequenceNumber " + getLastSequenceNumber());
        } catch (InterruptedException | EOFException | RuntimeException | BKException err) {
            LOGGER.log(Level.SEVERE, "Fatal error during recovery", err);
            signalLogFailed();
            throw new LogNotAvailableException(err);
        }
    }

    @Override
    public void startWriting() throws LogNotAvailableException {
        actualLedgersList = metadataManager.getActualLedgersList(tableSpaceUUID);
        openNewLedger();
    }

    @Override
    public void clear() throws LogNotAvailableException {
        this.currentLedgerId = 0;
        metadataManager.saveActualLedgersList(tableSpaceUUID, new LedgersInfo());
    }

    @Override
    public void dropOldLedgers(LogSequenceNumber lastCheckPointSequenceNumber) throws LogNotAvailableException {
        if (ledgersRetentionPeriod <= 0) {
            return;
        }
        LOGGER.log(Level.SEVERE, "dropOldLedgers lastCheckPointSequenceNumber: {0}, ledgersRetentionPeriod: {1} ,lastLedgerId: {2}, currentLedgerId: {3}",
            new Object[]{lastCheckPointSequenceNumber, ledgersRetentionPeriod, lastLedgerId, currentLedgerId});
        long min_timestamp = System.currentTimeMillis() - ledgersRetentionPeriod;
        List<Long> oldLedgers = actualLedgersList.getOldLedgers(min_timestamp);
        LOGGER.log(Level.SEVERE, "dropOldLedgers currentLedgerId: {0}, lastLedgerId: {1}, dropping ledgers before {2}: {3}",
            new Object[]{currentLedgerId, lastLedgerId, new java.sql.Timestamp(min_timestamp), oldLedgers});
        oldLedgers.remove(this.currentLedgerId);
        oldLedgers.remove(this.lastLedgerId);
        if (oldLedgers.isEmpty()) {
            return;
        }
        for (long ledgerId : oldLedgers) {
            try {
                LOGGER.log(Level.SEVERE, "dropping ledger {0}", ledgerId);
                actualLedgersList.removeLedger(ledgerId);
                try {
                    bookKeeper.deleteLedger(ledgerId);
                } catch (BKException.BKNoSuchLedgerExistsException error) {
                    LOGGER.log(Level.SEVERE, "error while dropping ledger " + ledgerId, error);
                }
                metadataManager.saveActualLedgersList(tableSpaceUUID, actualLedgersList);
                LOGGER.log(Level.SEVERE, "dropping ledger {0}, finished", ledgerId);
            } catch (BKException | InterruptedException error) {
                LOGGER.log(Level.SEVERE, "error while dropping ledger " + ledgerId, error);
                throw new LogNotAvailableException(error);
            }
        }
    }

    @Override
    public final void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closeCurrentWriter();
            closed = true;
            LOGGER.severe("closed");
        } finally {
            writer = null;
            lock.writeLock().unlock();
        }

    }

    private void closeCurrentWriter() {
        if (writer != null) {

            try {
                writer.close();
            } catch (Exception err) {
                LOGGER.log(Level.SEVERE, "error while closing ledger", err);
            } finally {
                writer = null;
            }
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean isFailed() {
        return failed;
    }

    @Override
    public void followTheLeader(LogSequenceNumber skipPast, BiConsumer<LogSequenceNumber, LogEntry> consumer) throws LogNotAvailableException {

        List<Long> actualList = metadataManager.getActualLedgersList(tableSpaceUUID).getActiveLedgers();

        List<Long> toRead = actualList;
        if (skipPast.ledgerId != -1) {
            toRead = toRead.stream().filter(l -> l >= skipPast.ledgerId).collect(Collectors.toList());
        }
        try {
            long nextEntry = skipPast.offset + 1;
//            LOGGER.log(Level.SEVERE, "followTheLeader "+tableSpace+" skipPast:{0} toRead: {1} actualList:{2}, nextEntry:{3}", new Object[]{skipPast, toRead, actualList, nextEntry});
            for (Long previous : toRead) {
                //LOGGER.log(Level.SEVERE, "followTheLeader openLedger " + previous + " nextEntry:" + nextEntry);
                LedgerHandle lh;
                try {
                    lh = bookKeeper.openLedgerNoRecovery(previous,
                        BookKeeper.DigestType.CRC32, sharedSecret.getBytes(StandardCharsets.UTF_8));
                } catch (BKException.BKLedgerRecoveryException e) {
                    LOGGER.log(Level.SEVERE, "error", e);
                    return;
                }
                try {
                    long lastAddConfirmed = lh.getLastAddConfirmed();
                    LOGGER.log(Level.FINE, "followTheLeader " + tableSpaceUUID + " openLedger {0} -> lastAddConfirmed:{1}, nextEntry:{2}", new Object[]{previous, lastAddConfirmed, nextEntry});
                    if (nextEntry > lastAddConfirmed) {
                        nextEntry = 0;
                        continue;
                    }
                    Enumeration<LedgerEntry> entries
                        = lh.readEntries(nextEntry, lh.getLastAddConfirmed());

                    while (entries.hasMoreElements()) {
                        LedgerEntry e = entries.nextElement();
                        long entryId = e.getEntryId();

                        byte[] entryData = e.getEntry();
                        LogEntry statusEdit = LogEntry.deserialize(entryData);
//                        LOGGER.log(Level.SEVERE, "" + tableSpaceUUID + " followentry {0},{1} -> {2}", new Object[]{previous, entryId, statusEdit});
                        LogSequenceNumber number = new LogSequenceNumber(previous, entryId);
                        lastSequenceNumber.accumulateAndGet(number.offset, EnsureLongIncrementAccumulator.INSTANCE);
                        lastLedgerId = number.ledgerId;
                        currentLedgerId = number.ledgerId;
                        consumer.accept(number, statusEdit);
                    }
                } finally {
                    try {
                        lh.close();
                    } catch (BKException err) {
                        LOGGER.log(Level.SEVERE, "error while closing ledger", err);
                    } catch (InterruptedException err) {
                        LOGGER.log(Level.SEVERE, "error while closing ledger", err);
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (InterruptedException | EOFException | BKException err) {
            LOGGER.log(Level.SEVERE, "internal error", err);
            throw new LogNotAvailableException(err);
        }
    }

    @Override
    public LogSequenceNumber getLastSequenceNumber() {
        return new LogSequenceNumber(lastLedgerId, lastSequenceNumber.get());
    }

}
