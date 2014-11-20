public class LockAVLTree<T extends Comparable<T>> implements Tree<T> {
	RootHolder<T> rootHolder;
	
	static final long Unlinked = 0x1L;
	static final long Growing = 0x2L;
	static final long GrowCountIncrement = 1L << 3;
	static final long GrowCountMask = 0xff << 3;
	static final long Shrinking = 0x4L;
	static final long ShrinkCountIncrement = 1L << 11;
	static final long IgnoreGrow = ~(Growing|GrowCountMask);
	
	// FIXME: the fuctions here are changed
    static long beginChange(long ovl) { return ovl | Shrinking; }
    static long endChange(long ovl) { return (ovl | (Shrinking|Unlinked)) + 1; }

    static boolean isShrinking(long ovl) { return (ovl & Shrinking) != 0; }
    static boolean isUnlinked(long ovl) { return (ovl & Unlinked) != 0; }
    static boolean isShrinkingOrUnlinked(long ovl) { return (ovl & (Shrinking|Unlinked)) != 0L; }
	
	static Object Retry = new Object();
	
	public LockAVLTree() {
		rootHolder = new RootHolder<T>();
	}
	
	@Override
	public boolean add(T value) {
		return attemptPut(value, rootHolder, 1, 0) == null;
	}

	@Override
	public boolean remove(T value) {
		return attemptRemove(value, rootHolder, 1, 0) != null;
	}

	@Override
	public boolean contains(T value) {
		return attemptGet(value, rootHolder, 1, 0) != null;
	}
	
	private Object attemptGet(T value, Node<T> node, int dir, long nodeVersion) {
		while(true) {
			Node<T> child = node.child(dir);
			if(((node.version ^ nodeVersion) & IgnoreGrow) != 0) {
				return Retry;
			}
			if(child == null) {
				return null;
			}
			int nextD = value.compareTo(child.value);
			if(nextD == 0) return child.value;
			long chV = child.version;
			if((chV & Shrinking) != 0) {
				waitUntilNotChanging(child);
			} else if(chV != Unlinked && child == node.child(dir)) {
				if(((node.version ^ nodeVersion) & IgnoreGrow) != 0) {
					return Retry;
				}
				Object p = attemptGet(value, child, nextD, chV);
				if(p != Retry) return p;
			}
		}
	}
	
	boolean canUnlink(Node<T> n) {
		return (n.left == null) || (n.right == null);
	}
	
	Object attemptPut(T value, Node<T> node, int dir, long nodeVersion) {
		Object p = Retry;
		do {
			Node<T> child = node.child(dir);
			if(((node.version ^ nodeVersion) & IgnoreGrow) != 0)
				return Retry;
			if(child == null) {
				p = attemptInsert(value, node, dir, nodeVersion);
			} else {
				int nextDir = value.compareTo(child.value);
				if(nextDir == 0)
					return null; // FIXME: No update, is that OK?
				else {
					long chV = child.version;
					if((chV & Shrinking) != 0) {
						waitUntilNotChanging(child);
					} else if(chV != Unlinked && child == node.child(dir)) {
						if(((node.version ^ nodeVersion) & IgnoreGrow) != 0)
							return Retry;
						p = attemptPut(value, child, nextDir, chV);
					}
				}
			}
			
		} while(p == Retry);
		return p;
	}
	
	Object attemptInsert(T value, Node<T> node, int dir, long nodeV) {
		synchronized(node) {
			if(((node.version ^ nodeV) & IgnoreGrow) != 0 ||
					node.child(dir) != null) {
				return Retry;
			}
			node.setChild(dir, new Node<T>(1, value, node, 0, null, null));
		}
		fixHeightAndRebalance(node);
		return null;
	}
	
	Object attemptRemove(T value, Node<T> node, int dir, long nodeV) {
		Object p = Retry;
		do {
			Node<T> child = node.child(dir);
			if(((node.version ^ nodeV) & IgnoreGrow) != 0)
				return Retry;
			if(child == null) {
				return null;
			} else {
				int nextDir = value.compareTo(child.value);
				if(nextDir == 0)
					p = attemptRmNode(node, child);
				else {
					long chV = child.version;
					if((chV & Shrinking) != 0) {
						waitUntilNotChanging(child);
					} else if(chV != Unlinked && child == node.child(dir)) {
						if(((node.version ^ nodeV) & IgnoreGrow) != 0)
							return Retry;
						p = attemptRemove(value, child, nextDir, chV); // Recurse
					}
				}
			}
			
		} while(p == Retry);
		return p;
	}
	
	Object attemptRmNode(Node<T> parent, Node<T> node) {
		if(node.value == null) return null;
		Object prev;
		if(!canUnlink(node)) {
			synchronized (node) {
				if(node.version == Unlinked || canUnlink(node))
					return Retry;
				prev = node.value;
				node.value = null; // change into Routing node
			}
		} else {
			synchronized(parent) {
				if(parent.version == Unlinked || node.parent != parent 
						|| node.version == Unlinked) {
					return Retry;
				}
				
				synchronized(node) {
					prev = node.value;
					node.value = null; // change into Routing node
					if(canUnlink(node)) {
						Node<T> c = node.left == null ? node.right: node.left;
						if(parent.left == node)
							parent.left = c;
						else
							parent.right = c;
						if(c != null) c.parent = parent;
						node.version = Unlinked;
					}
				}
			}
			fixHeightAndRebalance(parent);
		}
		return prev;
	}
	
	void rotateRight(Node<T> n) {
		Node<T> nP = n.parent, nL = n.left, nLR = nL.right;
		n.version |= Shrinking;
		nL.version |= Growing;
		
		n.left = nLR;
		nL.right = n;
		if(nP.left == n) nP.left = nL;
		else nP.right = nL;
		
		nL.parent = nP;
		n.parent = nL;
		if(nLR != null) nLR.parent = n;
		
		int h = 1 + Math.max(height(nLR), height(n.right));
		n.height = h;
		nL.height = 1 + Math.max(height(nL), h);
		
		nL.version += GrowCountIncrement;
		n.version += ShrinkCountIncrement;
	}
	
	

	
	static int SpinCount = 100;
	void waitUntilNotChanging(Node<T> n) {
		long v = n.version;
		if((v & (Growing | Shrinking)) != 0) {
			int i = 0;
			while(n.version == v && i < SpinCount) ++i;
			if(i==SpinCount)
				synchronized(n) { };
		}
	}
	
    private static class RootHolder<T> extends Node<T> {
        RootHolder() {
            super(1, null, null, 0L, null, null);
        }

        RootHolder(final RootHolder<T> snapshot) {
            super(1 + snapshot.height, null, null, 0L, null, snapshot.right);
        }
    }
	
	private static class Node<T> {
		public Node(int height, T value, Node<T> parent, long version,Node<T> left, Node<T> right) {
			this.height = height;
			this.version = version;
			this.value = value;
			this.parent = parent;
			this.left = left;
			this.right = right;
		}
		
		volatile int height;
		volatile long version;
		volatile T value;
		volatile Node<T> parent, left, right;
		
		public Node<T> child(int dir) {
			assert dir == 1 || dir == 0;
			return (dir == 0) ? left: right;
		}
		
		public void setChild(int dir, Node<T> child) throws IllegalArgumentException {
			if(dir == 0)
				this.left = child;
			else if(dir == 1)
				this.right = child;
			else
				throw new IllegalArgumentException ();
		}
		
		public Node<T> unsharedLeft() {
			Node<T> n = left;
			if(parent != null) {
				return n;
			}
			n.lazyCopyChildren();
			return left;
		}
		
		public Node<T> unsharedRight() {
			Node<T> n = right;
			if(parent != null) {
				return n;
			}
			n.lazyCopyChildren();
			return right;
		}
		
		private synchronized void lazyCopyChildren() {
			Node<T> cl = this.left;
			if(isShared(cl))
				this.left = cl.lazyCopy(this);
			Node<T> cr = this.right;
			if(isShared(cr))
				this.right = cr.lazyCopy(this);

		}
		
		private Node<T> lazyCopy(Node<T> newParent) {
			return new Node<T>(height, value, newParent, 0L,
					markShared(left), markShared(right));
		}
		
		private Node<T> markShared(Node<T> n) {
			if(n != null) n.parent = null;
			return n;
		}
		
		private boolean isShared(Node<T> n) {
			return n != null && n.parent == null;
		}
	}
	
    private int height(final Node<T> node) {
        return node == null ? 0 : node.height;
    }
	
	///////////////////////////// Adapted from /////////////////////////////////////////
	/* https://github.com/nbronson/snaptree/ */
    private static final int UnlinkRequired = -1;
    private static final int RebalanceRequired = -2;
    private static final int NothingRequired = -3;
	
	private int nodeCondition(final Node<T> node) {
        // Begin atomic.

        final Node<T> nL = node.left;
        final Node<T> nR = node.right;

        if ((nL == null || nR == null) && node.value == null) {
            return UnlinkRequired;
        }

        final int hN = node.height;
        final int hL0 = height(nL);
        final int hR0 = height(nR);

        // End atomic.  Since any thread that changes a node promises to fix
        // it, either our read was consistent (and a NothingRequired conclusion
        // is correct) or someone else has taken responsibility for either node
        // or one of its children.

        final int hNRepl = 1 + Math.max(hL0, hR0);
        final int bal = hL0 - hR0;

        if (bal < -1 || bal > 1) {
            return RebalanceRequired;
        }

        return hN != hNRepl ? hNRepl : NothingRequired;
    }

    private void fixHeightAndRebalance(Node<T> node) {
        while (node != null && node.parent != null) {
            final int condition = nodeCondition(node);
            if (condition == NothingRequired || isUnlinked(node.version)) {
                // nothing to do, or no point in fixing this node
                return;
            }

            if (condition != UnlinkRequired && condition != RebalanceRequired) {
                synchronized (node) {
                    node = fixHeight_nl(node);
                }
            } else {
                final Node<T> nParent = node.parent;
                synchronized (nParent) {
                    if (!isUnlinked(nParent.version) && node.parent == nParent) {
                        synchronized (node) {
                            node = rebalance_nl(nParent, node);
                        }
                    }
                    // else RETRY
                }
            }
        }
    }
    
    private Node<T> fixHeight_nl(final Node<T> node) {
        final int c = nodeCondition(node);
        switch (c) {
            case RebalanceRequired:
            case UnlinkRequired:
                // can't repair
                return node;
            case NothingRequired:
                // Any future damage to this node is not our responsibility.
                return null;
            default:
                node.height = c;
                // we've damaged our parent, but we can't fix it now
                return node.parent;
        }
    }
	
    /** nParent and n must be locked on entry.  Returns a damaged node, or null
     *  if no more rebalancing is necessary.
     */
    private Node<T> rebalance_nl(final Node<T> nParent, final Node<T> n) {

        final Node<T> nL = n.unsharedLeft();
        final Node<T> nR = n.unsharedRight();

        if ((nL == null || nR == null) && n.value == null) {
            if (attemptUnlink_nl(nParent, n)) {
                // attempt to fix nParent.height while we've still got the lock
                return fixHeight_nl(nParent);
            } else {
                // retry needed for n
                return n;
            }
        }

        final int hN = n.height;
        final int hL0 = height(nL);
        final int hR0 = height(nR);
        final int hNRepl = 1 + Math.max(hL0, hR0);
        final int bal = hL0 - hR0;

        if (bal > 1) {
            return rebalanceToRight_nl(nParent, n, nL, hR0);
        } else if (bal < -1) {
            return rebalanceToLeft_nl(nParent, n, nR, hL0);
        } else if (hNRepl != hN) {
            // we've got more than enough locks to do a height change, no need to
            // trigger a retry
            n.height = hNRepl;

            // nParent is already locked, let's try to fix it too
            return fixHeight_nl(nParent);
        } else {
            // nothing to do
            return null;
        }
    }

    private Node<T> rebalanceToRight_nl(final Node<T> nParent,
            final Node<T> n,
            final Node<T> nL,
            final int hR0) {
    	
		synchronized (nL) {
			final int hL = nL.height;
			if (hL - hR0 <= 1) {
				return n; // retry
			} else {
				final Node<T> nLR = nL.unsharedRight();
				final int hLL0 = height(nL.left);
				final int hLR0 = height(nLR);
				if (hLL0 >= hLR0) {
					// rotate right based on our snapshot of hLR
					return rotateRight_nl(nParent, n, nL, hR0, hLL0, nLR, hLR0);
				} else {
					synchronized (nLR) {
						// If our hLR snapshot is incorrect then we might
						// actually need to do a single rotate-right on n.
						final int hLR = nLR.height;
						if (hLL0 >= hLR) {
							return rotateRight_nl(nParent, n, nL, hR0, hLL0, nLR, hLR);
						} else {
							final int hLRL = height(nLR.left);
							final int b = hLL0 - hLRL;
							if (b >= -1 && b <= 1 && !((hLL0 == 0 || hLRL == 0) && nL.value == null)) {
							  // nParent.child.left won't be damaged after a double rotation
							  return rotateRightOverLeft_nl(nParent, n, nL, hR0, hLL0, nLR, hLRL);
							}
						}
					}
					// focus on nL, if necessary n will be balanced later   
					return rebalanceToLeft_nl(n, nL, nLR, hLL0);
				}
			}
		}
	}
    
    private Node<T> rebalanceToLeft_nl(final Node<T> nParent,
            final Node<T> n,
            final Node<T> nR,
            final int hL0) {
		synchronized (nR) {
			final int hR = nR.height;
			if (hL0 - hR >= -1) {
				return n; // retry
			} else {
				final Node<T> nRL = nR.unsharedLeft();
				final int hRL0 = height(nRL);
				final int hRR0 = height(nR.right);
				if (hRR0 >= hRL0) {
					return rotateLeft_nl(nParent, n, hL0, nR, nRL, hRL0, hRR0);
				} else {
					synchronized (nRL) {
						final int hRL = nRL.height;
						if (hRR0 >= hRL) {
							return rotateLeft_nl(nParent, n, hL0, nR, nRL, hRL, hRR0);
						} else {
							final int hRLR = height(nRL.right);
							final int b = hRR0 - hRLR;
							if (b >= -1 && b <= 1 && !((hRR0 == 0 || hRLR == 0) && nR.value == null)) {
							   return rotateLeftOverRight_nl(nParent, n, hL0, nR, nRL, hRR0, hRLR);
							}
						}
					}
					return rebalanceToRight_nl(n, nR, nRL, hRR0);
				}
			}
		}
	}
    
    private Node<T> rotateRight_nl(final Node<T> nParent,
            final Node<T> n,
            final Node<T> nL,
            final int hR,
            final int hLL,
            final Node<T> nLR,
            final int hLR) {
    	final long nodeOVL = n.version;

        final Node<T> nPL = nParent.left;

        n.version = beginChange(nodeOVL);

        n.left = nLR;
        if (nLR != null) {
            nLR.parent = n;
        }

        nL.right = n;
        n.parent = nL;

        if (nPL == n) {
            nParent.left = nL;
        } else {
            nParent.right = nL;
        }
        nL.parent = nParent;

        // fix up heights links
        final int hNRepl = 1 + Math.max(hLR, hR);
        n.height = hNRepl;
        nL.height = 1 + Math.max(hLL, hNRepl);

        n.version = endChange(nodeOVL);

        // We have damaged nParent, n (now parent.child.right), and nL (now
        // parent.child).  n is the deepest.  Perform as many fixes as we can
        // with the locks we've got.

        // We've already fixed the height for n, but it might still be outside
        // our allowable balance range.  In that case a simple fixHeight_nl
        // won't help.
        final int balN = hLR - hR;
        if (balN < -1 || balN > 1) {
            // we need another rotation at n
            return n;
        }

        // we've fixed balance and height damage for n, now handle
        // extra-routing node damage
        if ((nLR == null || hR == 0) && n.value == null) {
            // we need to remove n and then repair
            return n;
        }

        // we've already fixed the height at nL, do we need a rotation here?
        final int balL = hLL - hNRepl;
        if (balL < -1 || balL > 1) {
            return nL;
        }

        // nL might also have routing node damage (if nL.left was null)
        if (hLL == 0 && nL.value == null) {
            return nL;
        }

        // try to fix the parent height while we've still got the lock
        return fixHeight_nl(nParent);
    }
    
    private Node<T> rotateLeft_nl(final Node<T> nParent,
            final Node<T> n,
            final int hL,
            final Node<T> nR,
            final Node<T> nRL,
            final int hRL,
            final int hRR) {
    	final long nodeOVL = n.version;

        final Node<T> nPL = nParent.left;

        n.version = beginChange(nodeOVL);

        // fix up n links, careful to be compatible with concurrent traversal for all but n
        n.right = nRL;
        if (nRL != null) {
            nRL.parent = n;
        }

        nR.left = n;
        n.parent = nR;

        if (nPL == n) {
            nParent.left = nR;
        } else {
            nParent.right = nR;
        }
        nR.parent = nParent;

        // fix up heights
        final int  hNRepl = 1 + Math.max(hL, hRL);
        n.height = hNRepl;
        nR.height = 1 + Math.max(hNRepl, hRR);

        n.version = endChange(nodeOVL);

        final int balN = hRL - hL;
        if (balN < -1 || balN > 1) {
            return n;
        }

        if ((nRL == null || hL == 0) && n.value == null) {
            return n;
        }

        final int balR = hRR - hNRepl;
        if (balR < -1 || balR > 1) {
            return nR;
        }

        if (hRR == 0 && nR.value == null) {
            return nR;
        }

        return fixHeight_nl(nParent);
    }
    
    private Node<T> rotateRightOverLeft_nl(final Node<T> nParent,
            final Node<T> n,
            final Node<T> nL,
            final int hR,
            final int hLL,
            final Node<T> nLR,
            final int hLRL) {
    	final long nodeOVL = n.version;
        final long leftOVL = nL.version;

        final Node<T> nPL = nParent.left;
        final Node<T> nLRL = nLR.unsharedLeft();
        final Node<T> nLRR = nLR.unsharedRight();
        final int hLRR = height(nLRR);

        n.version = beginChange(nodeOVL);
        nL.version = beginChange(leftOVL);

        // fix up n links, careful about the order!
        n.left = nLRR;
        if (nLRR != null) {
            nLRR.parent = n;
        }

        nL.right = nLRL;
        if (nLRL != null) {
            nLRL.parent = nL;
        }

        nLR.left = nL;
        nL.parent = nLR;
        nLR.right = n;
        n.parent = nLR;

        if (nPL == n) {
            nParent.left = nLR;
        } else {
            nParent.right = nLR;
        }
        nLR.parent = nParent;

        // fix up heights
        final int hNRepl = 1 + Math.max(hLRR, hR);
        n.height = hNRepl;
        final int hLRepl = 1 + Math.max(hLL, hLRL);
        nL.height = hLRepl;
        nLR.height = 1 + Math.max(hLRepl, hNRepl);

        n.version = endChange(nodeOVL);
        nL.version = endChange(leftOVL);

        // caller should have performed only a single rotation if nL was going
        // to end up damaged
        assert(Math.abs(hLL - hLRL) <= 1);
        assert(!((hLL == 0 || nLRL == null) && nL.value == null));

        // We have damaged nParent, nLR (now parent.child), and n (now
        // parent.child.right).  n is the deepest.  Perform as many fixes as we
        // can with the locks we've got.

        // We've already fixed the height for n, but it might still be outside
        // our allowable balance range.  In that case a simple fixHeight_nl
        // won't help.
        final int balN = hLRR - hR;
        if (balN < -1 || balN > 1) {
            // we need another rotation at n
            return n;
        }

        // n might also be damaged by being an unnecessary routing node
        if ((nLRR == null || hR == 0) && n.value == null) {
            // repair involves splicing out n and maybe more rotations
            return n;
        }

        // we've already fixed the height at nLR, do we need a rotation here?
        final int balLR = hLRepl - hNRepl;
        if (balLR < -1 || balLR > 1) {
            return nLR;
        }

        // try to fix the parent height while we've still got the lock
        return fixHeight_nl(nParent);    
    }
    
    private Node<T> rotateLeftOverRight_nl(final Node<T> nParent,
            final Node<T> n,
            final int hL,
            final Node<T> nR,
            final Node<T> nRL,
            final int hRR,
            final int hRLR) {
    	final long nodeOVL = n.version;
        final long rightOVL = nR.version;

        final Node<T> nPL = nParent.left;
        final Node<T> nRLL = nRL.unsharedLeft();
        final Node<T> nRLR = nRL.unsharedRight();
        final int hRLL = height(nRLL);

        n.version = beginChange(nodeOVL);
        nR.version = beginChange(rightOVL);

        // fix up n links, careful about the order!
        n.right = nRLL;
        if (nRLL != null) {
            nRLL.parent = n;
        }

        nR.left = nRLR;
        if (nRLR != null) {
            nRLR.parent = nR;
        }

        nRL.right = nR;
        nR.parent = nRL;
        nRL.left = n;
        n.parent = nRL;

        if (nPL == n) {
            nParent.left = nRL;
        } else {
            nParent.right = nRL;
        }
        nRL.parent = nParent;

        // fix up heights
        final int hNRepl = 1 + Math.max(hL, hRLL);
        n.height = hNRepl;
        final int hRRepl = 1 + Math.max(hRLR, hRR);
        nR.height = hRRepl;
        nRL.height = 1 + Math.max(hNRepl, hRRepl);

        n.version = endChange(nodeOVL);
        nR.version = endChange(rightOVL);

        assert(Math.abs(hRR - hRLR) <= 1);

        final int balN = hRLL - hL;
        if (balN < -1 || balN > 1) {
            return n;
        }
        if ((nRLL == null || hL == 0) && n.value == null) {
            return n;
        }
        final int balRL = hRRepl - hNRepl;
        if (balRL < -1 || balRL > 1) {
            return nRL;
        }
        return fixHeight_nl(nParent);
    }
    

	private boolean attemptUnlink_nl(final Node<T> parent, final Node<T> node) {
	    // assert (Thread.holdsLock(parent));
	    // assert (Thread.holdsLock(node));
	    assert (!isUnlinked(parent.version));
	
	    final Node<T> parentL = parent.left;
	    final Node<T>  parentR = parent.right;
	    if (parentL != node && parentR != node) {
	        // node is no longer a child of parent
	        return false;
	    }
	
	    assert (!isUnlinked(node.version));
	    assert (parent == node.parent);
	
	    final Node<T> left = node.unsharedLeft();
	    final Node<T> right = node.unsharedRight();
	    if (left != null && right != null) {
	        // splicing is no longer possible
	        return false; 
	    }
	    final Node<T> splice = left != null ? left : right;
	
	    if (parentL == node) {
	        parent.left = splice; 
	    } else {
	        parent.right = splice;
	    }
	    if (splice != null) {
	        splice.parent = parent;
	    }
	
	    node.version = Unlinked;
	    node.value = null;
	
	    return true;
	}
}
