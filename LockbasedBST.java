// Coarse-grained BST

import java.util.concurrent.locks.*;

public class LockbasedBST<T extends Comparable<T>> implements Tree<T> {
	Lock master;
	Node root;
	
	public LockbasedBST() {
		master = new ReentrantLock();
		root = null;
	}
	
	@Override
	public boolean add(T value) {
		master.lock();
		try {
			if(root == null)
				root = new Node (value);
			else {
				Node parent = null;
				Node next = root;
				
				while(next != null) {
					int cmp = value.compareTo(next.value);
					parent = next;
					if(cmp == 0) {
						return false;
					} else if(cmp > 0) {
						 next = next.rchild;
					} else { // less than
						next = next.lchild;
					}
				}
				
				int cmp =  value.compareTo(parent.value);
				if(cmp > 0)
					parent.rchild = new Node(value);
				else // less than
					parent.lchild = new Node(value);
			}
		} finally {
			master.unlock();
		}
		
		return true;
	}

	@Override
	public boolean remove(T value) {
		master.lock();
		try {
			if(root == null)
				root = new Node (value);
			else {
				Node parent = null;
				Node next = root;
				
				while(true) {
					if(next == null) return false;
					
					int cmp = value.compareTo(next.value);
					parent = next;
					
					if(cmp == 0) {
						break;
					} else if(cmp > 0) {
						 next = next.rchild;
					} else { // less than
						next = next.lchild;
					}
				}
				
				// now next points to the value to be removed
				// and parent points to the parent				
				int cmp =  value.compareTo(parent.value);
				if(cmp > 0) {
					// next is parent's rchild
					int n = next.numChild();
					if(n == 0) {
						parent.rchild = null;
					} if(n == 1) {
						if(next.hasLchild())
							parent.rchild = next.lchild;
						else
							parent.rchild = next.rchild;
					} else { // has both left and right child
						Node r = next.rchild;
						Node rparent = null;
						while(r.hasLchild()) {
							rparent = r;
							r = r.lchild;
						}
						if(r.hasRchlid()) rparent.lchild = r.rchild;
						parent.rchild = r;
						r.lchild = next.lchild; r.rchild = next.rchild;
					}
				} else {
					// next is parent's lchild
					int n = next.numChild();
					if(n == 0) {
						parent.lchild = null;
					} if(n == 1) {
						if(next.hasLchild())
							parent.lchild = next.lchild;
						else
							parent.lchild = next.rchild;
					} else { // has both left and right child
						Node r = next.rchild;
						Node rparent = null;
						while(r.hasLchild()) {
							rparent = r;
							r = r.lchild;
						}
						if(r.hasRchlid()) rparent.lchild = r.rchild;
						parent.lchild = r;
						r.lchild = next.lchild; r.rchild = next.rchild;
					}
				}
					
			}
		} finally {
			master.unlock();
		}
		return false;
	}

	@Override
	public boolean contains(T value) {
		master.lock();
		try {
			Node next = root;
			while(next != null) {
				int cmp = value.compareTo(next.value);
				if(cmp == 0)
					return true;
				else if(cmp > 0)
					next = next.rchild;
				else // less than
					next = next.lchild;
			}
			return false;
		} finally {
			master.unlock();
		}
	}
	
	
	private class Node {
		public Node(T value) {
			this.value = value;
		}
		T value;
		Node lchild, rchild;
		
		public boolean isLeaf() {
			return (lchild == null && rchild == null);
		}
		
		public boolean hasLchild() { return lchild != null; }
		public boolean hasRchlid() { return rchild != null; }
		
		public int numChild() {
			return ((lchild==null)?1:0) + ((rchild==null)?1:0);
		}
		
	}
}
