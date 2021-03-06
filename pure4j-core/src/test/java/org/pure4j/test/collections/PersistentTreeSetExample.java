package org.pure4j.test.collections;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import junit.framework.Assert;

import org.junit.Test;
import org.pure4j.annotations.pure.Pure;
import org.pure4j.collections.IPersistentCollection;
import org.pure4j.collections.IPersistentSet;
import org.pure4j.collections.ITransientSet;
import org.pure4j.collections.PersistentTreeSet;
import org.pure4j.test.ShouldBePure;

public class PersistentTreeSetExample extends AbstractCollectionTest {

	@Pure
	@ShouldBePure
	public void pureMethod(IPersistentSet<String> in, int expected) {
		log("keys:"+in);
		
		List<String> l1 = new ArrayList<String>(in);
		Collections.sort(l1);
		String inStr = in.seq().toString();
		String l1Str = l1.toString();
		assertEquals(l1Str, inStr);
		
		in.cons("bobob");
	}
	
	@Pure
	@ShouldBePure
	public IPersistentCollection<String> getInstance() {
		return new PersistentTreeSet<String>();
	}
	
	@Test
	public void sanityTestOfSet() {
		
		// test persistence
		IPersistentSet<String> phm1 = new PersistentTreeSet<String>("bob", "jeff", "gogo", "zzap");
		IPersistentSet<String> phm = phm1.cons("spencer");
		pureMethod(phm, 4);
		phm = phm.cons("spencera");
		phm = phm.cons("spencerb");
		phm = phm.cons("spencerc");
		phm = phm.cons("spencerc");
		pureMethod(phm, 7);
	
		// test equality
		Assert.assertEquals(phm1, new PersistentTreeSet<String>("bob", "gogo", "jeff", "zzap"));
		
		ITransientSet<String> trans = phm1.asTransient();
		trans.add("boggins");
		Assert.assertEquals(new PersistentTreeSet<String>("bob", "boggins", "gogo", "jeff", "zzap"), trans.persistent());
		
		
	}
	
	@Test
	public void testConstruction() {
		// array
		int entries = 100;
		PersistentTreeSet<String> pm = new PersistentTreeSet<String>(makeSet(entries));
		assertEquals(entries, pm.size());
		System.out.println(pm);
		
		// iseq
		PersistentTreeSet<String> pm2 = new PersistentTreeSet<String>(pm.seq());
		assertEquals(pm2, pm);
//		
//		// map-based
		PersistentTreeSet<String> pm3 = new PersistentTreeSet<String>(pm);
		assertEquals(pm3, pm);
//		 
//		// no-args
		PersistentTreeSet<String> pm4 = new PersistentTreeSet<String>();
		for (String entry : pm) {
			pm4 = pm4.cons(entry);
		}
		assertEquals(pm, pm4);

	}
	

	private String[] makeSet(int i) {
		String[] out = new String[i];
		for (int j = 0; j < i; j++) {
			out[j] = "k"+j;
		}
		return out;
	}
}
