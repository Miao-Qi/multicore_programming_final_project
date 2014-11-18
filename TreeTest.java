import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.*;

public class TreeTest {
	static final int NUM_THREAD = 100;
	static Thread[] thread = new Thread[NUM_THREAD];
	
	// Subjects under test
	// declare as
	// static Tree<Integer
	// so that the test cases will work
	static Tree<Integer> Lockbased = new LockbasedBST<Integer>();
	
	
	public static class TestCase {
		String name;
		Tree<Integer> SUT;
		AtomicInteger numError;
		public TestCase(String nameOfTree, Tree<Integer> SUT) {
			this.name = nameOfTree;
			this.SUT = SUT;
			this.numError = new AtomicInteger(0);
		}
		
		Thread.UncaughtExceptionHandler h = new Thread.UncaughtExceptionHandler() {
		    public void uncaughtException(Thread th, Throwable ex) {
		        numError.getAndIncrement();
		    }
		};
		
		int run() throws InterruptedException, NullPointerException {			
			for(int i = 0; i < NUM_THREAD*5/10; i++) {
				thread[i] = new Thread() {
					ThreadLocalRandom rand = ThreadLocalRandom.current();
					public void run() {
						// TODO: write testing method
						SUT.add(rand.nextInt());
					}
				};
			}
			
			for(int i = NUM_THREAD*5/10; i < NUM_THREAD*9/10 ;i++) {
				thread[i] = new Thread() {
					ThreadLocalRandom rand = ThreadLocalRandom.current();
					public void run() {
						// TODO: write testing method
						SUT.remove(rand.nextInt());
					}
				};
			}
			
			for(int i = NUM_THREAD*9/10; i < NUM_THREAD; i++) {
				thread[i] = new Thread() {
					ThreadLocalRandom rand = ThreadLocalRandom.current();
					public void run() {
						// TODO: write testing method
						SUT.remove(rand.nextInt());
					}
				};
			}
			
			for (int i = 0; i < NUM_THREAD; i++) {
				thread[i].setUncaughtExceptionHandler(h);
				thread[i].start();
			}

			for (int i = 0; i < NUM_THREAD; i++) {
				thread[i].join();
			}
			
			return numError.get();
		}
	}
	
	public void test(TestCase tc) {
		System.out.print("Testing " + tc.name + ":... ");
        
        
        final int iterations = 100;
        
        // ignore first iteration
		try{
			tc.run();
        }catch(Exception e){
        }
		
		int sumTime = 0;
		int sumError = 0;
		for(int i = 0; i < iterations; i++) {
			long startTime = System.currentTimeMillis();
			int numError = 0;
			try{
				numError = tc.run();
	        }catch(Exception e){
	        	continue;
			}
	        long stopTime = System.currentTimeMillis();
	        
	        if(numError == 0)
	        	sumTime += (stopTime - startTime);
	        else
	        	sumError += numError;
		}
		
		System.out.println("Out of " + iterations + " executions, there were " + sumError + " errors.");
		
		if(sumError == iterations) return;
		int avgTime = sumTime / (iterations - sumError);
        System.out.println(String.format("%3d", avgTime) + " ms");
	}
	
	public static void main(String[] args) {
        System.out.println("Testing Different Tree Implementations");
        System.out.println("Number of Threads: " + NUM_THREAD);


        TreeTest MyTest = new TreeTest();

        // To add a new test case follow the template
        // MyTest.test(new TestCase("name of the tree", Tree t));
        MyTest.test(new TestCase("Lock-based BST", Lockbased));
	}

}
