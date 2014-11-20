import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;

public class LockFreeBST implements Tree<Integer> {
	Node[] root;
	boolean eagerHelpingEnabled;
	
	public LockFreeBST(boolean eagerHelpingEnabled) {
		root = new Node[2];
		root[0] = new Node(Integer.MIN_VALUE);
		root[1] = new Node(Integer.MAX_VALUE);
		root[0].set(Integer.MIN_VALUE,
							new myStampedRef(root[0], 0, 0, 1),
							new myStampedRef(root[1], 0, 0, 1),
							new AtomicReference<Node>(root[1]),  // backlink
							new AtomicReference<Node>(null));    // prelink
		root[1].set(Integer.MAX_VALUE,
							new myStampedRef(root[0], 0, 0, 0),
							new myStampedRef(null,    0, 0, 1),
							new AtomicReference<Node>(null),     // backlink
							new AtomicReference<Node>(null));    // prelink

		this.eagerHelpingEnabled = eagerHelpingEnabled;
	}
	
	@Override
	public boolean add(Integer value) {
		Node prev = root[1], curr = root[0]; // FIXME: what happens when swapping the two?
		Node node = new Node(value);
		node.child[0] = new myStampedRef(node, 0, 0, 1);
		while(true) {
			int dir = locate(prev, curr, value);
			if(dir == 2)
				return false;
			else {
				Node R = curr.child[dir].getReference();
				
				node.child[1] = new myStampedRef(R, 0, 0, 1);
				node.backlink.set(curr);
				// FIXME: change this to threaded to see whether it works
				if(curr.child[dir].compareAndSet(R, node, myStampedRef.THREAD, myStampedRef.THREAD))
					return true;
				else {
					// CAS fails, check if link has been marked, flagged, or a new Node was just
					// inserted. If marked or flagged, first help cleaning
					myStampedRef newR = curr.child[dir];
					if(newR.getReference() == R) {
						Node newCurr = prev;
						if(newR.mark())
							cleanMark(curr, dir);
						else if(newR.flag())
							cleanFlag(curr, R, prev, true);
						curr = newCurr;
						prev = newCurr.backlink.get();
					}
				}
			}
		}
	}

	@Override
	public boolean remove(Integer value) {
		if(isRoot(value)) return false; // never remove the roots
		
		Node prev = root[1], curr = root[0];
		int dir = locate(prev, curr, value-1); // FIXME: k-epsilon
		myStampedRef next = curr.child[dir];
		if(value != next.getReference().value)
			return false;
		else {
			boolean result = tryFlag(curr, next.getReference(), prev, true);
			if(prev.child[dir].getReference() == curr) {
				cleanFlag(curr, next.getReference(), prev, true);
			}
			
			return result;
		}
	}

	@Override
	public boolean contains(Integer value) {
		if(isRoot(value)) return false; // roots don't belong to data
		
		Node prev = root[1], curr = root[0];
		int dir = locate(prev, curr, value);
		return dir == 2;
	}
	
	private boolean isRoot(Integer value) { return value == Integer.MAX_VALUE || value == Integer.MAX_VALUE; }
	
	// return 2 when match
	private int locate(Node prev, Node curr, int value) {
		while(true) {
			int dir = cmp(value, curr.value); 
			if(dir == 2)
				return dir;
			else {
				myStampedRef R = curr.child[dir];
				
				// Eager-helping 
				if(eagerHelpingEnabled) {
					if(R.mark() && dir == 1) {
						Node newprev = prev.backlink.get();
						cleanMark(curr, dir); // FIXME: make sure this is correct
						prev = newprev;
						int pDir = cmp(value, prev.value);
						curr = prev.child[pDir].getReference();
					}
				}
				
				if(R.thread()) {
					int nextE = R.getReference().value;
					if(dir == 0 || value < nextE)
						return dir;
					else {
						prev = curr; curr = R.getReference();
					}
				}
			}
		}
	}
	
	static final int EQUAL = 2;
	static final int RIGHT = 1;
	static final int LEFT = 0;
	
	private int cmp(int a, int b) {
		if(a == b) return EQUAL;
		else if(a > b) return RIGHT;
		else return LEFT;
	}
	
	private boolean tryFlag(Node prev, Node curr, Node back, boolean isThread) {
		while(true) {
			int pDir = cmp(curr.value, prev.value) & 1; // 2 maps to 0: left link
			int t = isThread? 1:0;
			boolean result = prev.child[pDir].compareAndSet(curr, curr, t, myStampedRef.FLAG + t);
			if(result)
				return true;
			else {
				myStampedRef newR = prev.child[pDir];
				if(newR.getReference() == curr) {
					if(newR.flag()) return false;
					else if(newR.mark())
						cleanMark(prev, pDir);
					
					prev = back;
					pDir = cmp(curr.value, prev.value);
					Node newCurr = prev.child[pDir].getReference();
					locate(prev, newCurr, curr.value);
					if(newCurr != curr)
						return false;
					back = prev.backlink.get();
				}
			}
		}
	}
	
	private void tryMark(Node curr, int dir) {
		while(true) {
			Node back = curr.backlink.get();
			myStampedRef next = curr.child[dir];
			if(next.mark())
				break;
			else if(next.flag()) {
				if(!next.thread()) {
					cleanFlag(curr, next.getReference(), back, false);
					continue;
				} else if(dir == 1) {
					cleanFlag(curr, next.getReference(), back, true);
					continue;
				}
			}
			
			int t = next.thread()? 1:0;
			if(curr.child[dir].compareAndSet(next.getReference(), next.getReference(),
					t, myStampedRef.MARK + t))
				break;
		}	
	}
	
	void cleanFlag(Node prev, Node curr, Node back, boolean isThread) {
		if(isThread) {
			// cleaning a flagged order-link
			while(true) {
				myStampedRef next = curr.child[1];
				if(next.mark())
					break;
				else if(next.flag()) {
					if(back == next.getReference()) {
						back = back.backlink.get();
					}
					
					Node backNode = curr.backlink.get();
					cleanFlag(curr, next.getReference(), backNode, next.thread());
					if(back == next.getReference()) {
						int pDir = cmp(prev.value, backNode.value);
						prev = back.child[pDir].getReference();
					}
				} else {
					if(curr.prelink.get() != prev)
						curr.prelink.set(prev);
					int t = next.thread() ? 1:0;
					if(curr.child[1].compareAndSet(next.getReference(), next.getReference(),
							t, myStampedRef.MARK + t))
						break;
					
				}
			}
			cleanMark(curr, 1);
		} else {
			myStampedRef right = curr.child[1];
			if(right.mark()) {
				myStampedRef left = curr.child[0];
				Node preNode = curr.prelink.get();
				if(left.getReference() != preNode) {
					tryMark(curr, 0);
					cleanMark(curr, 0);
				} else {
					int pDir = cmp(curr.value, prev.value);
					if(left.getReference() == curr) {
						// FIXME: make sure this "right.flag()" is correct
						int f = (right.flag() ? 1:0)*myStampedRef.FLAG;
						int rT = right.thread() ? 1:0;
						prev.child[pDir].compareAndSet(curr, right.getReference(), f, rT);
						if(!right.thread())
							right.getReference().backlink.compareAndSet(curr, prev);
					} else {
						int rT = right.thread() ? 1:0;
						preNode.child[1].compareAndSet(curr, right.getReference(),
								myStampedRef.FLAG + myStampedRef.THREAD, rT);
						if(!right.thread()) {
							right.getReference().backlink.compareAndSet(curr, prev);
						}
						prev.child[pDir].compareAndSet(curr, preNode, myStampedRef.FLAG, rT);
						preNode.backlink.compareAndSet(curr, prev);
					}
				}
			} else if(right.flag() && right.thread()) {
				// the node is moving to replace its successor
				Node delNode = right.getReference();
				Node parent;
				while(true) {
					parent = delNode.backlink.get();
					int pDir = cmp(curr.value, prev.value);
					myStampedRef p = parent.child[pDir];
					if(p.mark()) cleanMark(parent, pDir);
					else if(p.flag()) break;
					else if(parent.child[pDir].compareAndSet(curr, curr, 0, myStampedRef.FLAG))
						break;
				}
				Node backNode = parent.backlink.get();
				cleanFlag(parent, curr, backNode, true);
			}
		}
	}
	
	private void cleanMark(Node curr, int markDir) {
		myStampedRef left = curr.child[0], right = curr.child[1];
		if(markDir == 1) {
			// TODO: WHAT IS delNode!???
			// TODO: what is pDir!?
			Node delNode = curr; // this is my guess
			
			while(true) {
				Node preNode = delNode.prelink.get();
				Node parent = delNode.backlink.get();
				int pDir = cmp(curr.value, parent.value); // p for parent here
						
				if(preNode == left.getReference()) {
					// category 1,2
					Node back = parent.backlink.get();
					tryFlag(parent, curr, back, true);
					if(parent.child[pDir].getReference() == curr) {
						cleanFlag(parent, curr, back, true);
						break;
					}
						
				} else {
					// category 3
					Node preParent = preNode.backlink.get();
					myStampedRef p = preParent.child[1];
					Node backNode = preParent.backlink.get();
					if(p.mark()) {
						cleanMark(preParent, 1);
					} else if(p.flag()) {
						cleanFlag(preParent, preNode, backNode, true);
					} else if(parent.child[pDir].compareAndSet(curr, curr, 0, myStampedRef.FLAG)) {
						cleanFlag(preParent, preNode, backNode, true);
						break;
					}
					
					// TODO: what is parent
				}
			}
			
		} else { // right link
			// the node is getting deleted or moved to replace its successor
			if(right.mark()) {
				// clean its left makred link
				Node preNode = curr.prelink.get();
				tryMark(preNode, 0);
				cleanMark(preNode, 0);
			} else {
				// curr change links accordingly
				Node delNode = right.getReference();
				Node delNodePa = delNode.backlink.get();
				Node preParent = curr.backlink.get();
				int pDir = cmp(delNode.value, delNodePa.value);
				myStampedRef delNodeL = delNode.child[0], delNodeR = delNode.child[1];
				int lt = left.thread() ? 1:0;
				int drT = delNodeR.thread()? 1:0;
				preParent.child[1].compareAndSet(curr, left.getReference(), lt, 0);
				curr.child[1].compareAndSet(right.getReference(), delNodeR.getReference(),
						myStampedRef.FLAG + myStampedRef.THREAD, drT);
				if(!delNodeR.thread()) {
					delNodeR.getReference().backlink.compareAndSet(delNode, curr);
				}
				delNodePa.child[pDir].compareAndSet(delNode, curr, myStampedRef.FLAG, 0);
				curr.backlink.compareAndSet(preParent, delNodePa);
			}
			
		}
	}
	
	private class myStampedRef extends AtomicStampedReference<Node> {
		public static final int THREAD = 0x01;
		public static final int MARK   = 0x02;
		public static final int FLAG   = 0x04;

		public myStampedRef(Node ref, int flag, int mark, int thread) {
			super(ref, ((flag << 2) | (mark << 1) | thread));
		}
		
		public boolean thread() {
			 return (this.getStamp() & THREAD) != 0;
		}
		
		public boolean mark() {
			 return (this.getStamp() & MARK) != 0;
		}
		
		public boolean flag() {
			 return (this.getStamp() & FLAG) != 0;
		}
	}
	
	private class Node {
		public Node(int value,
				myStampedRef child0,
				myStampedRef child1,
				AtomicReference<Node> backlink,
				AtomicReference<Node> prelink) {
			this.value = value;
			this.child = new myStampedRef[2];
			this.child[0] = child0;     // left child
			this.child[1] = child1;     // right child
			this.backlink = backlink;   // successor
			this.prelink = prelink;     // predecessor
		}
		
		public Node(int value) {
			this.value = value;
			this.child = new myStampedRef[2];
			this.backlink = new AtomicReference<Node>();
			this.prelink = new AtomicReference<Node>();
		}

		public void set(int value,
				myStampedRef child0,
				myStampedRef child1,
				AtomicReference<Node> backlink,
				AtomicReference<Node> prelink) {
			this.value = value;
			this.child = new myStampedRef[2];
			this.child[0] = child0;     // left child
			this.child[1] = child1;     // right child
			this.backlink = backlink;   // successor
			this.prelink = prelink;     // predecessor
		}
		
		volatile int value;
		volatile myStampedRef[] child;
		volatile AtomicReference<Node> backlink, prelink;		
	}


}
