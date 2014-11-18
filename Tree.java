
public interface Tree<T extends Comparable<T>> {
	boolean add(T value);
	boolean remove(T value);
	boolean contains(T value);
}
