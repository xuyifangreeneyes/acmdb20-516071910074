package simpledb;

import java.util.*;

/**
 * The Join operator implements the relational join operation.
 */
public class HashEquiJoin extends Operator {

    private static final long serialVersionUID = 1L;
    private static final int MAX_SIZE = 8192;

    private final JoinPredicate p;
    private DbIterator child1, child2;
    private TupleDesc td;
    private transient final HashMap<Field, ArrayList<Tuple>> hashMap = new HashMap<>();
    private transient Tuple tuple2;
    private transient Iterator<Tuple> listIt = null;

    /**
     * Constructor. Accepts to children to join and the predicate to join them
     * on
     * 
     * @param p
     *            The predicate to use to join the children
     * @param child1
     *            Iterator for the left(outer) relation to join
     * @param child2
     *            Iterator for the right(inner) relation to join
     */
    public HashEquiJoin(JoinPredicate p, DbIterator child1, DbIterator child2) {
        assert(p.getOperator() == Predicate.Op.EQUALS);
        this.p = p;
        this.child1 = child1;
        this.child2 = child2;
        this.td = TupleDesc.merge(child1.getTupleDesc(), child2.getTupleDesc());
    }

    public JoinPredicate getJoinPredicate() {
        return p;
    }

    public TupleDesc getTupleDesc() {
        return td;
    }
    
    public String getJoinField1Name()
    {
        return child1.getTupleDesc().getFieldName(getJoinPredicate().getField1());
    }

    public String getJoinField2Name()
    {
        return child2.getTupleDesc().getFieldName(getJoinPredicate().getField2());
    }

    private void fetchTuple2() throws DbException, TransactionAbortedException {
        if (child2.hasNext()) {
            tuple2 = child2.next();
        } else {
            tuple2 = null;
        }
    }

    private void setInitState() throws DbException, TransactionAbortedException {
        buildHashMap();
        fetchTuple2();
        listIt = null;
    }

    public void open() throws DbException, NoSuchElementException,
            TransactionAbortedException {
        child1.open();
        child2.open();
        setInitState();
        super.open();
    }

    public void close() {
        child1.close();
        child2.close();
        hashMap.clear();
        tuple2 = null;
        listIt = null;
        super.close();
    }

    public void rewind() throws DbException, TransactionAbortedException {
        child1.rewind();
        child2.rewind();
        setInitState();
        // ensure that Operator.next == null
        super.close();
        super.open();
    }

    private void buildHashMap() throws DbException, TransactionAbortedException {
        hashMap.clear();
        while (child1.hasNext() && hashMap.size() < MAX_SIZE) {
            Tuple tuple = child1.next();
            Field field = tuple.getField(p.getField1());
            if (!hashMap.containsKey(field)) {
                hashMap.put(field, new ArrayList<>());
            }
            hashMap.get(field).add(tuple);
        }
    }

    /**
     * Returns the next tuple generated by the join, or null if there are no
     * more tuples. Logically, this is the next tuple in r1 cross r2 that
     * satisfies the join predicate. There are many possible implementations;
     * the simplest is a nested loops join.
     * <p>
     * Note that the tuples returned from this particular implementation of Join
     * are simply the concatenation of joining tuples from the left and right
     * relation. Therefore, there will be two copies of the join attribute in
     * the results. (Removing such duplicate columns can be done with an
     * additional projection operator if needed.)
     * <p>
     * For example, if one tuple is {1,2,3} and the other tuple is {1,5,6},
     * joined on equality of the first column, then this returns {1,2,3,1,5,6}.
     * 
     * @return The next matching tuple.
     * @see JoinPredicate#filter
     */
    protected Tuple fetchNext() throws TransactionAbortedException, DbException {
        while (!hashMap.isEmpty()) {
            while (tuple2 != null) {
                if (listIt == null) {
                    Field field = tuple2.getField(p.getField2());
                    if (hashMap.containsKey(field)) {
                        listIt = hashMap.get(field).listIterator();
                    } else {
                        fetchTuple2();
                        continue;
                    }
                }
                if (listIt.hasNext()) {
                    Tuple tuple1 = listIt.next();
                    assert(p.filter(tuple1, tuple2));
                    Tuple tuple3 = new Tuple(td);
                    int len1 = child1.getTupleDesc().numFields(), len2 = child2.getTupleDesc().numFields();
                    for (int i = 0; i < len1; ++i) {
                        tuple3.setField(i, tuple1.getField(i));
                    }
                    for (int i = 0; i < len2; ++i) {
                        tuple3.setField(len1 + i, tuple2.getField(i));
                    }
                    return tuple3;
                } else {
                    fetchTuple2();
                    listIt = null;
                }
            }
            buildHashMap();
            child2.rewind();
            fetchTuple2();
            listIt = null;
        }
        return null;
    }

    @Override
    public DbIterator[] getChildren() {
        return new DbIterator[] {child1, child2};
    }

    @Override
    public void setChildren(DbIterator[] children) {
        child1 = children[0];
        child2 = children[1];
        td = TupleDesc.merge(child1.getTupleDesc(), child2.getTupleDesc());
    }
    
}
