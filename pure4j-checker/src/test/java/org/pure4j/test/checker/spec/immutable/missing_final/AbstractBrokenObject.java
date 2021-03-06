package org.pure4j.test.checker.spec.immutable.missing_final;

import org.pure4j.exception.FieldNotFinalException;
import org.pure4j.exception.PureMethodAccessesNonFinalFieldException;
import org.pure4j.test.CausesError;
import org.pure4j.test.ShouldBePure;

public abstract class AbstractBrokenObject {

	@CausesError(FieldNotFinalException.class)
	protected Integer in;

	
	@ShouldBePure
	public Integer getIn() {
		return in;
	}

	@ShouldBePure
	public void setIn(Integer in) {
		this.in = in;
	}

	@ShouldBePure
	public AbstractBrokenObject(Integer in) {
		super();
		this.in = in;
	}
	
}
