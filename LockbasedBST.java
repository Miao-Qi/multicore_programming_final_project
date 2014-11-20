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
				return false;
				
			Node parent = null;
			Node next = root;
			
			while(true) {
				if(next == null)
					return false;
				
				int cmp = value.compareTo(next.value);
				
				parent = next;
				if(cmp == 0) {
					break; // Found!
				} else if(cmp > 0) {
					 next = next.rchild;
				} else { // less than
					next = next.lchild;
				}
			}
			
			if(next.isLeaf()) { // leaf
				if(parent == null) { // root
					root = null;
				} else if(value.compareTo(parent.value) > 0) {
					parent.rchild = null;
				} else {
					parent.lchild = null;
				}
			}
			
			else if(!next.hasLchild()) {
				Node child = next.rchild;
				next.swap(child);
				next.lchild = child.lchild;
				next.rchild = child.rchild;
			}
			
			else if(!next.hasRchlid()) {
				Node child = next.lchild;
				next.swap(child);
				next.lchild = child.lchild;
				next.rchild = child.rchild;
			}
			
			else { // has both children
				Node child = next.lchild;
				parent = null;
				while(child.hasRchlid()) {
					parent = child;
					child = child.rchild;
				}
				
				if(parent == null) {
					next.swap(child);
					next.lchild = child.lchild;
					next.rchild = child.rchild;
				} else {
					next.swap(child);
					parent.rchild = child.lchild;
				}
				
			}
		} finally {
			master.unlock();
		}
		return true;
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
		
		public boolean hasLchild() { return lchild != null; }
		public boolean hasRchlid() { return rchild != null; }
		public boolean isLeaf() { return lchild == null && rchild == null; } 
		
		public void swap(Node other) {
			T tmp = this.value;
			this.value = other.value;
			other.value = tmp; 
		}
	}
}
