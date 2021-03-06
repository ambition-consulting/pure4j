package org.pure4j.processor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pure4j.Pure4J;
import org.pure4j.annotations.immutable.IgnoreImmutableTypeCheck;
import org.pure4j.annotations.pure.Enforcement;
import org.pure4j.annotations.pure.Pure;
import org.pure4j.annotations.pure.PurityType;
import org.pure4j.exception.ClassExpectingPureMethod;
import org.pure4j.exception.ClassHierarchyUsesBothTypesOfPurity;
import org.pure4j.exception.IncorrectPure4JImmutableCallException;
import org.pure4j.exception.MemberCantBeHydratedException;
import org.pure4j.exception.MissingImmutableParameterCheckException;
import org.pure4j.exception.PureMethodAccessesNonFinalFieldException;
import org.pure4j.exception.PureMethodAccessesNonImmutableFieldException;
import org.pure4j.exception.PureMethodCallsImpureException;
import org.pure4j.exception.PureMethodNotInProjectScopeException;
import org.pure4j.exception.PureMethodOnNonImmutableClassException;
import org.pure4j.exception.PureMethodParameterNotImmutableException;
import org.pure4j.exception.PureMethodReturnNotImmutableException;
import org.pure4j.model.CallInfo;
import org.pure4j.model.ClassInitHandle;
import org.pure4j.model.ConstructorHandle;
import org.pure4j.model.FieldHandle;
import org.pure4j.model.ImplementationHandle;
import org.pure4j.model.MemberHandle;
import org.pure4j.model.MethodHandle;
import org.pure4j.model.ProjectModel;
import org.pure4j.model.StackArgumentsHandle;
import org.springframework.asm.Opcodes;

/**
 * The responsibility of this class is to keep track of all the methods we want
 * to check for purity. The {@link PureMethod} implementations track each
 * individual call and self-check the purity. This is done on a per
 * method-declaration level.
 * 
 * @author robmoffat
 *
 */
public class PureChecklistHandler {

	public static final boolean IGNORE_EXCEPTION_CONSTRUCTION = true;
	public static final boolean IGNORE_EQUALS_PARAMETER_PURITY = true;
	public static final boolean IGNORE_TOSTRING_PURITY = true;
	public static final boolean IGNORE_ENUM_VALUES_PURITY = true;

	public class PureMethod {

		public MemberHandle declaration;
		public Enforcement implPurity;
		public Enforcement intfPurity;
		private Boolean pureImplementation = null;
		private Boolean pureInterface = null;
		private PurityType pt;
		private Set<Class<?>> usedIn = new LinkedHashSet<Class<?>>();

		public Set<Class<?>> getUsedIn() {
			return usedIn;
		}

		private PureMethod(MemberHandle declaration, Enforcement impl, Enforcement intf, PurityType pt) {
			super();
			this.declaration = declaration;
			this.implPurity = impl;
			this.intfPurity = intf;
			setupEnforcements(intf, impl);
			this.pt = pt;
		}

		protected void setupEnforcements(Enforcement intf, Enforcement impl) {
			if (impl == Enforcement.FORCE) {
				pureImplementation = true;
				pureInterface = true;
			} else if (impl == Enforcement.NOT_PURE) {
				pureImplementation = false;
				pureInterface = false;
			}
			
			if (intf == Enforcement.FORCE) {
				pureInterface = true;
			} else if (intf == Enforcement.NOT_PURE) {
				pureInterface = false;
			}
		}

		private PureMethod(String description, Enforcement impl, Enforcement intf) throws ClassNotFoundException {
			int firstDot = description.indexOf(".");
			int firstBracket = description.indexOf("(");
			String classPart = description.substring(0, firstDot);
			String methodPart = description.substring(firstDot + 1, firstBracket);
			String descPart = description.substring(firstBracket);
			this.declaration = (methodPart.equals("<init>")) ? new ConstructorHandle(classPart, descPart, 0) : new MethodHandle(classPart, methodPart, descPart, 0);

			this.implPurity = impl;
			this.intfPurity = intf;
			setupEnforcements(intf, impl);
		}

		@Override
		public String toString() {
			return "   See:[declaration=" + declaration + "\n     impl=" + implPurity + "\n     intf=" + intfPurity + ", \n     purity=" + pt+", \n     usedIn=\n" + lines(usedIn, 10) + "       ]";
		}

		private String lines(Set<Class<?>> usedIn2, int i) {
			StringBuilder sb = new StringBuilder();
			for (Class<?> class1 : usedIn2) {
				for (int j = 0; j < i; j++) {
					sb.append(' ');
				}
				sb.append(class1.toString());
				sb.append("\n");
			}
			
			return sb.toString();
		}
		
		public Boolean checkInterfacePurity(Callback cb, ProjectModel pm) {
			if ((pureInterface == null) && (checkInterface)) {
				pureInterface = true;
				
				if (!pm.withinModel(declaration.getClassName())) {
					return pureInterface;
				}
				
				if (IGNORE_EQUALS_PARAMETER_PURITY) {
					if (("equals".equals(declaration.getName())) && ("(Ljava/lang/Object;)Z".equals(declaration.getDesc()))) {
						return true;
					}
				}

				
				// check the signature of accessible pure method.
				// only public/package visible methods need to be checked.
				
				if (isAccessibleOutsideClass(declaration, cl, pm)) {
					Type[] genericTypes = declaration.getGenericTypes(cl);
					int argOffset = getArgOffset();
					for (int i = thisFieldSkip(); i < genericTypes.length; i++) {
						Type t = genericTypes[i];
						if (typeFailsImmutabilityCheck(cb, t)) {
							int argNo = i+ argOffset;
							if (!isRuntimeChecked(argNo, pm, cb)) {
								cb.registerError(new PureMethodParameterNotImmutableException(this, t));
								pureInterface = false;
								return false;
							}
						}
					}
					
					if (pt == PurityType.IMMUTABLE_VALUE) {
						if (!Modifier.isStatic(declaration.getModifiers(cl))) {
							CallInfo ci = pm.getOpcodes(declaration);
							if (ci.usesThis()) {
								// if we use 'this', then the class must be immutable.
								for (Class<?> c : usedIn) {
									if (typeFailsImmutabilityCheck(cb, c)) {
										cb.registerError(new PureMethodOnNonImmutableClassException(this, c));
										pureInterface = false;
										return false;
									}
								}
							}
						}
					}
					
					
					if (pt == PurityType.MUTABLE_UNSHARED) {
						if (declaration instanceof MethodHandle) {
							if (declaration.getAnnotation(cl, IgnoreImmutableTypeCheck.class) == null) {
								Method m = ((MethodHandle) declaration).hydrate(cl);
								Type t = m.getGenericReturnType();
								if (typeFailsImmutabilityCheck(cb, t) && (!returnsOwnType()))  {
									pureInterface = isRuntimeReturnChecked(pm, cb);
								}
							}
						} 
					}
				}
			}
			
			return pureInterface;
		}

		public Boolean checkImplementationPurity(Callback cb, ProjectModel pm) {
			if ((pureImplementation == null) && (checkImpl)) {
				try {
					pureImplementation = true;

					if (!pm.withinModel(declaration.getClassName())) {
						// we can't confirm that the method is pure, unless it
						// has been forced already.
						if (isMarkedPure(declaration, cb)) {
							pureImplementation = true;
							return true;
						} else {
							cb.registerError(new PureMethodNotInProjectScopeException(this));
							pureImplementation = false;
							return false;
						}
					}
					
					CallInfo ci = pm.getOpcodes(declaration);
					if ((ci.getOpcodes() & Opcodes.ACC_SYNTHETIC) == Opcodes.ACC_SYNTHETIC) {
						// synthetics are always pure.
						pureImplementation = true;
						return true;
					}

					if (this.declaration.getDeclaringClass(cl).isInterface()) {
						pureImplementation = true;
						return true;
					}

					if (IGNORE_ENUM_VALUES_PURITY && declaration.getDeclaringClass(cl).isEnum() && declaration.getName().equals("values")) {
						return true;
					}

					if (declaration instanceof MethodHandle) {
						Method m = ((MethodHandle) declaration).hydrate(cl);
						if (Modifier.isAbstract(m.getModifiers())) {
							return true;
						}
					}
					
					List<MemberHandle> calls = pm.getCalls(declaration);
					for (MemberHandle mh : calls) {
						if (mh instanceof ImplementationHandle) {
							mh = ensureCorrectClass(mh);
							if ((IGNORE_TOSTRING_PURITY) && (mh.getName().equals("toString")) && (mh.getDeclaringClass().equals("java/lang/Object"))) {
								// we can skip this one
							} else if (!isMarkedPure(mh, cb)) {
								cb.registerError(new PureMethodCallsImpureException(this, mh));
								pureImplementation = false;
							}
						} else if (mh instanceof FieldHandle) {
							FieldHandle fieldHandle = (FieldHandle) mh;
							Field f = fieldHandle.hydrate(cl);
							boolean isStatic = Modifier.isStatic(f.getModifiers());
							boolean isFinal = Modifier.isFinal(f.getModifiers());
							
							if (isStatic) {
								if (!isFinal) {
									cb.registerError(new PureMethodAccessesNonFinalFieldException(this, fieldHandle));
									pureImplementation = false;
								}
									
								if (!immutables.typeIsMarked(f.getGenericType(), cb)) {
									if ((!forcedImmutable(f)) && (isAccessibleOutsideClass(fieldHandle, cl, pm))) {
										cb.registerError(new PureMethodAccessesNonImmutableFieldException(this, fieldHandle));
										pureImplementation = false;
									}
								}
							}
							
						} 
					}
				} catch (MemberCantBeHydratedException e) {
					cb.registerError(e);
					pureImplementation = false;
				}

				cb.send("Pure implementation: " + this);

			}
			return pureImplementation;
		}

		private boolean returnsOwnType() {
			String returnedType = declaration.getDesc().substring(declaration.getDesc().lastIndexOf(")"));
			return (returnedType.contains(declaration.getDeclaringClass()));
		}

		protected boolean typeFailsImmutabilityCheck(Callback cb, Type t) {
			if (intfPurity == Enforcement.CHECKED) {
				return !immutables.typeIsMarked(t, cb);
			} 
			
			return false;
		}

		private int thisFieldSkip() {
			if (declaration instanceof ConstructorHandle) {
				Constructor<?> c = ((ConstructorHandle)declaration).hydrate(cl);
				int countOfThisFields = 0;	// for 'this'
				for (Field f : c.getDeclaringClass().getDeclaredFields()) {
					if (f.getName().startsWith("this$")) {
						countOfThisFields++;
					}
				}
				
				return countOfThisFields > 0 ? 1 : 0;
			}
			
			return 0;
		}
		
		private int getArgOffset() {
			if (declaration instanceof ConstructorHandle) {
				return 1;
			} else if (declaration instanceof ClassInitHandle) {
				return 0;
			} else {
				// method handle
				Method m = ((MethodHandle) declaration).hydrate(cl);
				if (Modifier.isStatic(m.getModifiers())) {
					return 0;
				} else {
					return 1;
				}
				
			}
		}

		private boolean forcedImmutable(Field f) {
			IgnoreImmutableTypeCheck iv = f.getAnnotation(IgnoreImmutableTypeCheck.class);
			if ((iv != null)) {
				return true;
			}

			return false;
		}

		private boolean isRuntimeReturnChecked(ProjectModel pm, Callback cb) {
			CallInfo ci = pm.getOpcodes(declaration);
			
			for (Object mh : ci.getMethodsBeforeReturns()) {
				if (mh instanceof Integer) {
					int lineNumber = (Integer) mh;
					cb.registerError(new PureMethodReturnNotImmutableException(this, lineNumber));
					return false;
				} else if (mh instanceof ImplementationHandle) {
					MemberHandle ch = (MemberHandle) mh;
					if (ch.getClassName().equals(org.springframework.asm.Type.getInternalName(Pure4J.class)) &&
						(ch.getName().equals("returnImmutable")) ) {
					// return ok.
					
					} else {
						cb.registerError(new PureMethodReturnNotImmutableException(this, ch.getLine()));
						return false;
					}
				}
			}
			
			return true;
		}
		
		/**
		 * Checks to see whether the developer has called the runtime check for
		 * immutability.
		 */
		private boolean isRuntimeChecked(int paramNo, ProjectModel pm, Callback cb) {
			List<MemberHandle> calls = pm.getCalls(declaration);
			if (isCovariantMethod(calls)) {
				return true;
			}
			boolean found = false;
			boolean checkTried = false;
			for (MemberHandle memberHandle : calls) {
				if (memberHandle instanceof StackArgumentsHandle) {
					StackArgumentsHandle icmh = (StackArgumentsHandle) memberHandle;
					if (icmh.getName().equals("immutable")) {
						checkTried = true;
						if (!icmh.isFirstCall()) {
							cb.registerError(new IncorrectPure4JImmutableCallException(this));
							return false;
						} else if (icmh.getLocalVariables().contains(paramNo)) {
							found = true;
						}
					} else if (icmh.getName().equals("unsupported")) {
						checkTried = true;
						found = true;
					} else if (icmh.getName().equals("<init>")) {
						if (icmh.getLocalVariables().contains(paramNo)) {
							found = true;
						}
					}
				}
			}

			if (checkTried) {
				if (!found) {
					cb.registerError(new MissingImmutableParameterCheckException(this, paramNo));
					return false;
				}
			}

			return checkTried;
		}
		
		/**
		 * If this is a covariant return type implementation, we don't need to
		 * bother with checking, it'll be done in the more specific method.
		 */
		private boolean isCovariantMethod(List<MemberHandle> calls) {
			if (!(this.declaration instanceof MethodHandle)) {
				return false;
			}
			
			if ((calls.size() == 1) && (calls.get(0) instanceof MethodHandle)) {
				MethodHandle theCall = (MethodHandle) calls.get(0);
				if (theCall.getName().equals(this.declaration.getName())) {
					Method theCallMethod = theCall.hydrate(cl);
					Method fromMethod = ((MethodHandle) this.declaration).hydrate(cl);
					if (theCallMethod.getReturnType().isAssignableFrom(fromMethod.getReturnType())) {
						if (theCallMethod.getParameterTypes().length == fromMethod.getParameterTypes().length) {
							return true;
						}
					}
				}
			}

			return false;
		}
	}

	private ClassLoader cl;
	private ClassAnnotationCache immutables;
	private ClassAnnotationCache mutableUnshared;
	private boolean checkInterface;
	private boolean checkImpl;

	public PureChecklistHandler(ClassLoader cl, ClassAnnotationCache immutables, ClassAnnotationCache mutableUnshared, boolean intf, boolean impl) {
		super();
		this.cl = cl;
		this.immutables = immutables;
		this.mutableUnshared = mutableUnshared;
		this.checkInterface = intf;
		this.checkImpl = impl;
		loadPureLists();
	}

	private Map<MemberHandle, PureMethod> pureChecklist = new HashMap<MemberHandle, PureMethod>();

	public void loadPureLists() {
		try {
			load("/java-lang.pure");
			load("/java-builtins.pure");
			load("/java-extra.pure");
		} catch (Exception e) {
			throw new RuntimeException("Couldn't load the pure lists: ", e);
		}
	}

	public void load(String fileName) throws IOException, ClassNotFoundException {
		InputStream is = getInputStreamForResource(fileName);
		BufferedReader br = new BufferedReader(new InputStreamReader(is));
		String line = br.readLine();
		while (line != null) {
			String[] parts = line.trim().split(" ");
			Enforcement impl = Enforcement.valueOf(parts[0]);
			PureMethod pureMethod = new PureMethod(parts[1], impl, null);
			pureChecklist.put(pureMethod.declaration, pureMethod);
			line = br.readLine();
		}
	}

	/**
	 * Tries both the classloader for pure4j, and also the checked-code classloader.
	 * @param fileName Resource to load
	 * @return InputStream to the resource.  pure4j library gets priority.
	 */
	protected InputStream getInputStreamForResource(String fileName) {
		InputStream is = PureChecklistHandler.class.getResourceAsStream(fileName.startsWith("/") ? fileName : "/"+fileName);
		if (is == null) {
			is = cl.getResourceAsStream(fileName);
		}
		return is;
	}

	private static boolean isAnonymousInnerClass(String className) {
		String tail = className.substring(className.lastIndexOf("$")+1);
		boolean out = tail.matches("[0-9]*");
		//int mods = PureChecklistHandler.class.getModifiers();
		return out;
	}
	
	
	public boolean isMarkedPure(MemberHandle mh, Callback cb) {
		boolean staticMethod = false;
		if (mh instanceof MethodHandle) {
			staticMethod = mh.getName().equals("<clinit>") || Modifier.isStatic(mh.getModifiers(cl));
		}
		
		if (pureChecklist.containsKey(mh)) {
			PureMethod pm = pureChecklist.get(mh);
			return pm.implPurity != Enforcement.NOT_PURE;
		}

		if (IGNORE_EQUALS_PARAMETER_PURITY) {
			if (("equals".equals(mh.getName())) && ("(Ljava/lang/Object;)Z".equals(mh.getDesc()))) {
				return true;
			}
		}
		
		if ((mh instanceof ConstructorHandle) && (isAnonymousInnerClass(mh.getClassName()))) {
			// because you can't add the pure annotation to anonymous inner classes, we assume it is pure.
			return true;
		}

		Pure p = mh.getAnnotation(cl, Pure.class);
		if (p != null) {
			return p.value() != Enforcement.NOT_PURE;
		}

		if (staticMethod) {
			if (isInnerClassAccessMethod(mh, cb)) {
				return true;
			}
			
			return false;
		}

		// interfaces now allowed as pure
		if (mh.getDeclaringClass(cl).isInterface()) {
			return true;
		}

		if (isMarkedPure(mh.getDeclaringClass(cl), cb)) {
			return true;
		}

		if (IGNORE_EXCEPTION_CONSTRUCTION) {
			if (Throwable.class.isAssignableFrom(mh.getDeclaringClass(cl))) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Normalize so that we are calling the class where the method is declared.
	 */
	private MemberHandle ensureCorrectClass(MemberHandle mh) {
		if (mh instanceof MethodHandle) {
			Method m = ((MethodHandle)mh).hydrate(cl);
			MethodHandle m2 = new MethodHandle(m);
			return m2;
		}
		return mh;
	}

	private boolean isInnerClassAccessMethod(MemberHandle mh, Callback cb) {
		if (mh instanceof MethodHandle) {
			Method m = ((MethodHandle) mh).hydrate(cl);
			if ((m.getName().startsWith("access$")) && 
					(m.getParameterTypes().length >= 1)) {
				
				Type t = m.getGenericParameterTypes()[0];
				if (t instanceof Class<?>) {
					return isMarkedPure((Class<?>) t, cb);
				}
			}
		}	
		return false;
	}
	
	public boolean isMarkedPure(Class<?> in, Callback cb) {
		if ((in == null) || (in == Object.class)) {
			return false;
		}

		if (immutables.classIsMarked(in, cb)) {
			return true;
		}
		
		if (mutableUnshared.classIsMarked(in, cb)) {
			return true;
		}
		
		Pure p = in.getAnnotation(Pure.class);
		if ((p != null) && (p.value() != Enforcement.NOT_PURE)) {
			return true;
		}

		for (Class<?> intf : in.getInterfaces()) {
			if (isMarkedPure(intf, cb)) {
				return true;
			}
		}

		if (isMarkedPure(in.getSuperclass(), cb)) {
			return true;
		}

		return false;
	}

	public void addMethod(MemberHandle declaration, Enforcement impl, Enforcement intf, PurityType pt, Class<?> usedIn, Callback cb) {
		PureMethod pm;
		if (pureChecklist.containsKey(declaration)) {
			pm = pureChecklist.get(declaration);
		} else {
			pm = new PureMethod(declaration, impl, intf, pt);
			pureChecklist.put(declaration, pm);
			cb.send("  - " + declaration + " " + impl);
		}

		if (pm.implPurity != impl) {
			if (pm.implPurity != Enforcement.FORCE) {
				cb.registerError(new ClassExpectingPureMethod(usedIn, pm));
			}
		}
		
		if (pm.implPurity != Enforcement.NOT_PURE) {
			if ((pm.pt != pt) && (pm.pt != null)) {
				cb.registerError(new ClassHierarchyUsesBothTypesOfPurity(pm, usedIn));
			}
		}

		pm.usedIn.add(usedIn);
	}

	public PureMethod getElementFor(MemberHandle mh) {
		return pureChecklist.get(mh);
	}

	public void doPureMethodChecks(Callback cb, ProjectModel pm) {
		for (PureMethod pureCandidate : pureChecklist.values()) {
			if (checkImpl) {
				pureCandidate.checkImplementationPurity(cb, pm);
			}
			if (checkInterface) {
				pureCandidate.checkInterfacePurity(cb, pm);
			}
		}
	}

	public Collection<PureMethod> getMethodList() {
		return pureChecklist.values();
	}

	public static boolean isAccessibleOutsideClass(MemberHandle handle, ClassLoader cl, ProjectModel pm) {
		boolean pub = Modifier.isPublic(handle.getModifiers(cl));
		boolean priv = Modifier.isPrivate(handle.getModifiers(cl));
		boolean prot = Modifier.isProtected(handle.getModifiers(cl));
		boolean synthetic = false;
		CallInfo ci = pm.getOpcodes(handle);
		synthetic = (Opcodes.ACC_SYNTHETIC & ci.getOpcodes()) == Opcodes.ACC_SYNTHETIC;
		if (synthetic) {
			return false;
		}
		
		if (PurityChecker.classIsAnonymousInner(handle.getDeclaringClass(cl))) {
			return false;
		}
		
		return pub || ((!priv) && (!prot));
	}
}
