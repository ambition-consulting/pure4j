package org.pure4j.checker.basic.pure_methods;

import org.pure4j.annotations.pure.Pure;
import org.pure4j.checker.basic.good.SomeValueObject;

public class SomePureStuff {

	@Pure
	public static int getLetterCount(String in) {
		return in.length();
	}
	
	@Pure
	public static int doStuffWithValueObject(SomeValueObject svo) {
		return svo.getNameLength()+svo.getAge()+getLetterCount("blah");
	}
}