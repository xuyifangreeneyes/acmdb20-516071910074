package simpledb;

import java.io.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BufferPool manages the reading and writing of pages into memory from
 * disk. Access methods call into it to retrieve pages, and it fetches
 * pages from the appropriate location.
 * <p>
 * The BufferPool is also responsible for locking;  when a transaction fetches
 * a page, BufferPool checks that the transaction has the appropriate
 * locks to read/write the page.
 * 
 * @Threadsafe, all fields are final
 */
public class BufferPool {
    /** Bytes per page, including header. */
    private static final int PAGE_SIZE = 4096;

    private static int pageSize = PAGE_SIZE;
    
    /** Default number of pages passed to the constructor. This is used by
    other classes. BufferPool should use the numPages argument to the
    constructor instead. */
    public static final int DEFAULT_PAGES = 50;

    private final ConcurrentHashMap<PageId, Page> pages = new ConcurrentHashMap<>();
    private final int numPages;
    private final Random generator = new Random();

    private final LockManager lockManager = new LockManager();

    /**
     * Creates a BufferPool that caches up to numPages pages.
     *
     * @param numPages maximum number of pages in this buffer pool.
     */
    public BufferPool(int numPages) {
        this.numPages = numPages;
    }
    
    public static int getPageSize() {
      return pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void setPageSize(int pageSize) {
    	BufferPool.pageSize = pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void resetPageSize() {
    	BufferPool.pageSize = PAGE_SIZE;
    }

    /**
     * Retrieve the specified page with the associated permissions.
     * Will acquire a lock and may block if that lock is held by another
     * transaction.
     * <p>
     * The retrieved page should be looked up in the buffer pool.  If it
     * is present, it should be returned.  If it is not present, it should
     * be added to the buffer pool and returned.  If there is insufficient
     * space in the buffer pool, an page should be evicted and the new page
     * should be added in its place.
     *
     * @param tid the ID of the transaction requesting the page
     * @param pid the ID of the requested page
     * @param perm the requested permissions on the page
     */
    public Page getPage(TransactionId tid, PageId pid, Permissions perm)
        throws TransactionAbortedException, DbException {

        lockManager.requireLock(tid, pid, perm);

        if (pages.containsKey(pid)) {
            return pages.get(pid);
        }
        if (pages.size() == numPages) {
            evictPage();
        }
        DbFile f = Database.getCatalog().getDatabaseFile(pid.getTableId());
        Page page = f.readPage(pid);
        pages.put(pid, page);
        return page;
    }

    /**
     * Releases the lock on a page.
     * Calling this is very risky, and may result in wrong behavior. Think hard
     * about who needs to call this and why, and why they can run the risk of
     * calling it.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param pid the ID of the page to unlock
     */
    public  void releasePage(TransactionId tid, PageId pid) {
        lockManager.releaseLock(tid, pid);
    }

    /**
     * Release all locks associated with a given transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     */
    public void transactionComplete(TransactionId tid) throws IOException {
        transactionComplete(tid, true);
    }

    /** Return true if the specified transaction has a lock on the specified page */
    public boolean holdsLock(TransactionId tid, PageId pid) {
        return lockManager.holdLock(tid, pid);
    }

    /**
     * Commit or abort a given transaction; release all locks associated to
     * the transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param commit a flag indicating whether we should commit or abort
     */
    public void transactionComplete(TransactionId tid, boolean commit)
        throws IOException {
        Set<PageId> lockedPids = lockManager.getHeldLocks(tid);
        if (lockedPids == null) {
            // The transaction never calls getPage. Is it possible?
            return;
        }

        if (commit) {
            flushPages(tid);
        } else {
            rollbackPages(tid);
        }

        // release locks held by the transaction
        lockManager.releaseLocks(tid);
    }

    private void updateDirtiedPages(TransactionId tid, ArrayList<Page> dirtiedPages) {
        for (Page dirtied : dirtiedPages) {
            dirtied.markDirty(true, tid);
            // evict some page when buffer pool is full
            PageId pid = dirtied.getId();
            if (!pages.containsKey(pid) && pages.size() == numPages) {
                try {
                    evictPage();
                } catch (DbException e) {
                    e.printStackTrace();
                }
            }
            pages.put(pid, dirtied);
        }
    }

    /**
     * Add a tuple to the specified table on behalf of transaction tid.  Will
     * acquire a write lock on the page the tuple is added to and any other 
     * pages that are updated (Lock acquisition is not needed for lab2). 
     * May block if the lock(s) cannot be acquired.
     * 
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction adding the tuple
     * @param tableId the table to add the tuple to
     * @param t the tuple to add
     */
    public void insertTuple(TransactionId tid, int tableId, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        DbFile f = Database.getCatalog().getDatabaseFile(tableId);
        ArrayList<Page> dirtiedPages = f.insertTuple(tid, t);
        updateDirtiedPages(tid, dirtiedPages);
    }

    /**
     * Remove the specified tuple from the buffer pool.
     * Will acquire a write lock on the page the tuple is removed from and any
     * other pages that are updated. May block if the lock(s) cannot be acquired.
     *
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction deleting the tuple.
     * @param t the tuple to delete
     */
    public  void deleteTuple(TransactionId tid, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        assert t.getRecordId() != null;
        int tableId = t.getRecordId().getPageId().getTableId();
        DbFile f = Database.getCatalog().getDatabaseFile(tableId);
        ArrayList<Page> dirtiedPages = f.deleteTuple(tid, t);
        updateDirtiedPages(tid, dirtiedPages);
    }

    /**
     * Flush all dirty pages to disk.
     * NB: Be careful using this routine -- it writes dirty data to disk so will
     *     break simpledb if running in NO STEAL mode.
     */
    public synchronized void flushAllPages() throws IOException {
        for (PageId pid : pages.keySet()) {
            flushPage(pid);
        }
    }

    /** Remove the specific page id from the buffer pool.
        Needed by the recovery manager to ensure that the
        buffer pool doesn't keep a rolled back page in its
        cache.
        
        Also used by B+ tree files to ensure that deleted pages
        are removed from the cache so they can be reused safely
    */
    public synchronized void discardPage(PageId pid) {
        pages.remove(pid);
    }

    /**
     * Flushes a certain page to disk
     * @param pid an ID indicating the page to flush
     */
    private synchronized  void flushPage(PageId pid) throws IOException {
        if (!pages.containsKey(pid)) {
            return;
        }
        Page page = pages.get(pid);
        if (page.isDirty() == null) {
            return;
        }
        DbFile f = Database.getCatalog().getDatabaseFile(pid.getTableId());
        f.writePage(page);
        page.markDirty(false, null);
    }

    /** Write all pages of the specified transaction to disk.
     */
    public synchronized  void flushPages(TransactionId tid) throws IOException {
        Set<PageId> lockedPids = lockManager.getHeldLocks(tid);
        if (lockedPids == null) {
            return;
        }
        for (PageId pid : lockedPids) {
            Page page = pages.get(pid);
            if (page == null || page.isDirty() == null) {
                continue;
            }
            assert page.isDirty() == tid;
//            PageLock lock = pageLocks.get(pid);
//            assert lock != null && lock.isExclusive(tid);
            flushPage(pid);
            page.setBeforeImage();
        }
    }

    /**
     * Rollback dirtied pages when aborting the transaction.
     */
    public synchronized void rollbackPages(TransactionId tid) {
        Set<PageId> lockedPids = lockManager.getHeldLocks(tid);
        if (lockedPids == null) {
            return;
        }
        for (PageId pid : lockedPids) {
            Page page = pages.get(pid);
            if (page == null || page.isDirty() == null) {
                continue;
            }
            assert page.isDirty() == tid;
            Page beforeImage = page.getBeforeImage();
            assert beforeImage != null;
            pages.put(pid, beforeImage);
        }
    }

    /**
     * Discards a page from the buffer pool.
     * Flushes the page to disk to ensure dirty pages are updated on disk.
     */
    private synchronized  void evictPage() throws DbException {
        ArrayList<PageId> pids = new ArrayList<>(pages.keySet());
        int start = generator.nextInt(pids.size());
        for (int i = 0; i < pids.size(); ++i) {
            PageId pid = pids.get((start + i) % pids.size());
            if (pages.get(pid).isDirty() == null) {
                try {
                    flushPage(pid);
                } catch (IOException e) {
                    throw new DbException("flushPage throws an IOException when evicting a page");
                }
                pages.remove(pid);
                return;
            }
        }
        throw new DbException("there is no clean page in BufferPool");
    }

}
