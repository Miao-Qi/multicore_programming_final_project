import stanford.SnapTreeMap;

public class LockAVLTreeInterface<T extends Comparable<T>> implements Tree<T> {
	SnapTreeMap<T, T>st = new SnapTreeMap<T, T>();
	
	@Override
	public boolean add(T value) {
		return st.put(value, value) != null;
	}

	@Override
	public boolean remove(T value) {
		return st.remove(value) != null;
	}

	@Override
	public boolean contains(T value) {
		return st.containsKey(value);
	}

}
