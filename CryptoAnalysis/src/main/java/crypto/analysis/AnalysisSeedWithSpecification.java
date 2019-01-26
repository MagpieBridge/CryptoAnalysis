package crypto.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.crypto.SecretKey;

import java.util.Set;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;

import boomerang.ForwardQuery;
import boomerang.debugger.Debugger;
import boomerang.jimple.AllocVal;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import boomerang.results.ForwardBoomerangResults;
import crypto.analysis.errors.IncompleteOperationError;
import crypto.analysis.errors.TypestateError;
import crypto.constraints.ConstraintSolver;
import crypto.constraints.ConstraintSolver.EvaluableConstraint;
import crypto.extractparameter.CallSiteWithParamIndex;
import crypto.extractparameter.ExtractParameterAnalysis;
import crypto.interfaces.ICryptSLPredicateParameter;
import crypto.interfaces.ISLConstraint;
import crypto.predicates.PredicateHandler;
import crypto.rules.CryptSLCondPredicate;
import crypto.rules.CryptSLMethod;
import crypto.rules.CryptSLPredicate;
import crypto.rules.StateNode;
import crypto.rules.TransitionEdge;
import crypto.typestate.CryptSLMethodToSootMethod;
import crypto.typestate.ErrorStateNode;
import crypto.typestate.ExtendedIDEALAnaylsis;
import crypto.typestate.SootBasedStateMachineGraph;
import crypto.typestate.WrappedState;
import ideal.IDEALSeedSolver;
import soot.IntType;
import soot.Local;
import soot.RefType;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.jimple.AssignStmt;
import soot.jimple.Constant;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.jimple.ThrowStmt;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;
import sync.pds.solver.nodes.Node;
import typestate.TransitionFunction;
import typestate.finiteautomata.ITransition;
import typestate.finiteautomata.State;

public class AnalysisSeedWithSpecification extends IAnalysisSeed {

	private final ClassSpecification spec;
	private ExtendedIDEALAnaylsis analysis;
	private ForwardBoomerangResults<TransitionFunction> results;
	private Multimap<Statement, State> typeStateChange = HashMultimap.create();
	private ConstraintSolver constraintSolver;
	protected Map<Statement, SootMethod> allCallsOnObject = Maps.newHashMap();
	private ExtractParameterAnalysis parameterAnalysis;
	private boolean secure = true;
	private boolean internalConstraintSatisfied;
	

	public AnalysisSeedWithSpecification(CryptoScanner cryptoScanner, Statement stmt, Val val,
			ClassSpecification spec) {
		super(cryptoScanner, stmt, val, spec.getFSM().getInitialWeight(stmt));
		this.spec = spec;
		this.analysis = new ExtendedIDEALAnaylsis() {

			@Override
			public SootBasedStateMachineGraph getStateMachine() {
				return spec.getFSM();
			}

			@Override
			protected BiDiInterproceduralCFG<Unit, SootMethod> icfg() {
				return cryptoScanner.icfg();
			}

			@Override
			protected Debugger<TransitionFunction> debugger(IDEALSeedSolver<TransitionFunction> solver) {
				return cryptoScanner.debugger(solver, AnalysisSeedWithSpecification.this);
			}

			@Override
			public CrySLResultsReporter analysisListener() {
				return cryptoScanner.getAnalysisListener();
			}
		};
	}

	@Override
	public String toString() {
		return "AnalysisSeed [" + super.toString() + " with spec " + spec.getRule().getClassName() + "]";
	}

	public void execute() {
		cryptoScanner.getAnalysisListener().seedStarted(this);
		runTypestateAnalysis();
		if(results == null)
			//Timeout occured.
			return;

		allCallsOnObject = results.getInvokedMethodOnInstance();
		runExtractParameterAnalysis();
		computeTypestateErrorUnits();
		computeTypestateErrorsForEndOfObjectLifeTime();
		
		cryptoScanner.getAnalysisListener().onSeedFinished(this, results);
	}



	//Is this only necessary for SecretKey key = keygen.generateKey(); because no rules say we got to start here. In which state to start?
	@Override
	public void addPotentialPredicates() {
		for(Cell<Statement, Val, TransitionFunction> c : results.asStatementValWeightTable().cellSet()){
			for(CryptSLPredicate p : potentialPredicates) {
				ensuredPredicates.add(new RequiredCryptSLPredicate(p, c.getRowKey()));
				ensuredPredicatesAtStatement.put(c.getRowKey(), new RequiredCryptSLPredicate(p, c.getRowKey()));
			}
		}
	}

	private void runTypestateAnalysis() {
		analysis.run(this);
		results = analysis.getResults();
	}

	
	private void runExtractParameterAnalysis() {
		this.parameterAnalysis = new ExtractParameterAnalysis(this.cryptoScanner, allCallsOnObject, spec.getFSM());
		this.parameterAnalysis.findDataFlowForNonRuleTypes();
	}
	
	private void computeTypestateErrorUnits() {
		Set<Statement> allTypestateChangeStatements = Sets.newHashSet();
		for (Cell<Statement, Val, TransitionFunction> c : results.asStatementValWeightTable().cellSet()) {
			allTypestateChangeStatements.addAll(c.getValue().getLastStateChangeStatements());
		}
		for (Cell<Statement, Val, TransitionFunction> c : results.asStatementValWeightTable().cellSet()) {
			Statement curr = c.getRowKey();
			if(allTypestateChangeStatements.contains(curr)) {
				Collection<? extends State> targetStates = getTargetStates(c.getValue());
				for (State newStateAtCurr : targetStates) {
					typeStateChangeAtStatement(curr, newStateAtCurr);
				}
			}
				
		}
	}

	private void computeTypestateErrorsForEndOfObjectLifeTime() {
		Table<Statement, Val, TransitionFunction> endPathOfPropagation = results.getObjectDestructingStatements();

		for (Cell<Statement, Val, TransitionFunction> c : endPathOfPropagation.cellSet()) {
			Set<SootMethod> expectedMethodsToBeCalled = Sets.newHashSet();
			for (ITransition n : c.getValue().values()) {
				if (n.to() == null)
					continue;
				if (!n.to().isAccepting()) {
					if (n.to() instanceof WrappedState) {
						WrappedState wrappedState = (WrappedState) n.to();
						for (TransitionEdge t : spec.getRule().getUsagePattern().getAllTransitions()) {
							if (t.getLeft().equals(wrappedState.delegate())) {
								Collection<SootMethod> converted = CryptSLMethodToSootMethod.v().convert(t.getLabel());
								expectedMethodsToBeCalled.addAll(converted);
							}
						}
					}
				}
			}
			if (!expectedMethodsToBeCalled.isEmpty()) {
				Statement s = c.getRowKey();
				Val val = c.getColumnKey();
				if(!(s.getUnit().get() instanceof ThrowStmt)){
					cryptoScanner.getAnalysisListener().reportError(this, new IncompleteOperationError(s, val, getSpec().getRule(), this, 
							expectedMethodsToBeCalled));
				}
			}
		}
	}

	private void typeStateChangeAtStatement(Statement curr, State stateNode) {
		if(typeStateChange.put(curr, stateNode)) {
			if (stateNode instanceof ErrorStateNode) {
				ErrorStateNode errorStateNode = (ErrorStateNode) stateNode;
				cryptoScanner.getAnalysisListener().reportError(this, new TypestateError(curr, getSpec().getRule(), this, errorStateNode.getExpectedCalls()));
			}
		}
		onAddedTypestateChange(curr, stateNode);
	}

	private void onAddedTypestateChange(Statement curr, State stateNode) {
		for (CryptSLPredicate potentialPredicate : spec.getRule().getPredicates()) {
			if (potentialPredicate.isNegated()) {
				continue;
			}

			if (!curr.isCallsite()) {
				continue;
			}
			if (isPotentiallyPredicateGeneratingState(potentialPredicate, stateNode)) {
				triggerNewDataFlowForPotenialPredicate(curr, stateNode, potentialPredicate);
			}
		}
	}

	private void triggerNewDataFlowForPotenialPredicate(Statement currStmt, State stateNode, CryptSLPredicate potentialPredicate) {
//		//This logic is wrong here.
		for (ICryptSLPredicateParameter predicateParam : potentialPredicate.getParameters()) {
//			if (predicateParam.getName().equals("this")) {
				expectPredicateWhenThisObjectIsInState(stateNode, currStmt, potentialPredicate);
//			}
		}
		InvokeExpr ie = ((Stmt) currStmt.getUnit().get()).getInvokeExpr();
		SootMethod invokedMethod = ie.getMethod();
		Collection<CryptSLMethod> convert = CryptSLMethodToSootMethod.v().convert(invokedMethod);

		for (CryptSLMethod cryptSLMethod : convert) {
			Entry<String, String> retObject = cryptSLMethod.getRetObject();
			if (!retObject.getKey().equals("_") && currStmt.getUnit().get() instanceof AssignStmt
					&& predicateParameterEquals(potentialPredicate.getParameters(), retObject.getKey())) {
				AssignStmt as = (AssignStmt) currStmt.getUnit().get();
				Value leftOp = as.getLeftOp();
				AllocVal val = new AllocVal(leftOp, currStmt.getMethod(), as.getRightOp(), new Statement(as, currStmt.getMethod()));
				
				//Do we need somthing along the lines?
//				Set<IAnalysisSeed> seeds = cryptoScanner.findSeedsForValAtStatement(new Node<Statement,Val>(currStmt,val), false);
//				if(seeds.isEmpty())
				
				Type baseType = val.value().getType();
				boolean matched = false;
				if (baseType instanceof RefType) {
					RefType refType = (RefType) baseType;
					for (ClassSpecification spec : cryptoScanner.getClassSpecifictions()) {
						if (spec.getRule().getClassName().equals(refType.getSootClass().getName())) {
							//This is a weird way;
							AnalysisSeedWithSpecification seed = cryptoScanner.getOrCreateSeedWithSpec(
									new AnalysisSeedWithSpecification(cryptoScanner, currStmt, val, spec));
							//What about the predicate?
							if(spec.getRule().toString().contains("SecretKey")) {
								seed.addPotentiallyEnsuredPredicate(potentialPredicate);
							}
							seed.addRequiredPredicate(this, new RequiredCryptSLPredicate(potentialPredicate, currStmt));
						}
						matched = true;
					}
				}
				if(!matched) {
					AnalysisSeedWithEnsuredPredicate seed = cryptoScanner
							.getOrCreateSeed(new Node<Statement, Val>(currStmt, val));
					System.out.println(seed);
					seed.addPotentiallyEnsuredPredicate(potentialPredicate);
					seed.addRequiredPredicate(this, new RequiredCryptSLPredicate(potentialPredicate, currStmt));
				}
			}
				
			int i = 0;
			for (Entry<String, String> p : cryptSLMethod.getParameters()) {
				if (predicateParameterEquals(potentialPredicate.getParameters(), p.getKey())) {
					Value param = ie.getArg(i);
					if (param instanceof Local) {
						Val val = new Val(param, currStmt.getMethod());
						//Potential for being unsound: The function findSeedsForValAtStatement requires the analysis of other seed to be done... 
						// Would need another fix-point iteration
						Set<IAnalysisSeed> seeds = cryptoScanner.findSeedsForValAtStatement(new Node<Statement,Val>(currStmt,val), true);
						for(IAnalysisSeed s : seeds) {
							s.addPotentiallyEnsuredPredicate(potentialPredicate);
							s.addRequiredPredicate(this, new RequiredCryptSLPredicate(potentialPredicate, currStmt));
						}
					}
				}
				i++;
			}

		}

	}

	private boolean predicateParameterEquals(List<ICryptSLPredicateParameter> parameters, String key) {
		for (ICryptSLPredicateParameter predicateParam : parameters) {
			if (key.equals(predicateParam.getName())) {
				return true;
			}
		}
		return false;
	}


	

	private void expectPredicateWhenThisObjectIsInState(State stateNode, Statement currStmt,
			CryptSLPredicate potentialPredicate) {
		if (results == null)
			return;
		for (Cell<Statement, Val, TransitionFunction> e : results.asStatementValWeightTable().cellSet()) {
			if (containsTargetState(e.getValue(), stateNode)) {
				ensuredPredicates.add(new RequiredCryptSLPredicate(potentialPredicate,  e.getRowKey()));
				ensuredPredicatesAtStatement.put(e.getRowKey(), new RequiredCryptSLPredicate(potentialPredicate, e.getRowKey()));
			}
		}
	}

	private boolean containsTargetState(TransitionFunction value, State stateNode) {
		return getTargetStates(value).contains(stateNode);
	}

	private Collection<? extends State> getTargetStates(TransitionFunction value) {
		Set<State> res = Sets.newHashSet();
		for (ITransition t : value.values()) {
			if (t.to() != null)
				res.add(t.to());
		}
		return res;
	}


	public boolean checkPredicates() {
		if(!internalConstraintSatisfied) {
			return false;
		}
		return super.checkPredicates();
	}

//	private boolean doPredsMatch(CryptSLPredicate pred, EnsuredCryptSLPredicate ensPred) {
//		boolean requiredPredicatesExist = true;
//		for (int i = 0; i < pred.getParameters().size(); i++) {
//			String var = pred.getParameters().get(i).getName();
//			if (isOfNonTrackableType(var)) {
//				continue;
//			} else if (pred.getInvolvedVarNames().contains(var)) {
//
//				final String parameterI = ensPred.getPredicate().getParameters().get(i).getName();
//				Collection<String> actVals = Collections.emptySet();
//				Collection<String> expVals = Collections.emptySet();
//
//				for (CallSiteWithParamIndex cswpi : ensPred.getParametersToValues().keySet()) {
//					if (cswpi.getVarName().equals(parameterI)) {
//						actVals = retrieveValueFromUnit(cswpi, ensPred.getParametersToValues().get(cswpi));
//					}
//				}
//				for (CallSiteWithParamIndex cswpi : parameterAnalysis.getCollectedValues().keySet()) {
//					if (cswpi.getVarName().equals(var)) {
//						expVals = retrieveValueFromUnit(cswpi, parameterAnalysis.getCollectedValues().get(cswpi));
//					}
//				}
//
//				String splitter = "";
//				int index = -1;
//				if (pred.getParameters().get(i) instanceof CryptSLObject) {
//					CryptSLObject obj = (CryptSLObject) pred.getParameters().get(i);
//					if (obj.getSplitter() != null) {
//						splitter = obj.getSplitter().getSplitter();
//						index = obj.getSplitter().getIndex();
//					}
//				}
//				for (String foundVal : expVals) {
//					if (index > -1) {
//						foundVal = foundVal.split(splitter)[index];
//					}
//					requiredPredicatesExist &= actVals.contains(foundVal);
//				}
//			} else {
//				requiredPredicatesExist = false;
//			}
//		}
//		return pred.isNegated() != requiredPredicatesExist;
//	}

	private Collection<String> retrieveValueFromUnit(CallSiteWithParamIndex cswpi, Collection<ForwardQuery> collection) {
		Collection<String> values = new ArrayList<String>();
		for (ForwardQuery q : collection) {
			Unit u = q.stmt().getUnit().get();
			if (cswpi.stmt().equals(q.stmt())) {
				if (u instanceof AssignStmt) {
					values.add(retrieveConstantFromValue(
							((AssignStmt) u).getRightOp().getUseBoxes().get(cswpi.getIndex()).getValue()));
				} else {
					values.add(retrieveConstantFromValue(u.getUseBoxes().get(cswpi.getIndex()).getValue()));
				}
			} else if (u instanceof AssignStmt) {
				final Value rightSide = ((AssignStmt) u).getRightOp();
				if (rightSide instanceof Constant) {
					values.add(retrieveConstantFromValue(rightSide));
				} else {
					final List<ValueBox> useBoxes = rightSide.getUseBoxes();

					// varVal.put(callSite.getVarName(),
					// retrieveConstantFromValue(useBoxes.get(callSite.getIndex()).getValue()));
				}
			}
			// if (u instanceof AssignStmt) {
			// final List<ValueBox> useBoxes = ((AssignStmt) u).getRightOp().getUseBoxes();
			// if (!(useBoxes.size() <= cswpi.getIndex())) {
			// values.add(retrieveConstantFromValue(useBoxes.get(cswpi.getIndex()).getValue()));
			// }
			// } else if (cswpi.getStmt().equals(u)) {
			// values.add(retrieveConstantFromValue(cswpi.getStmt().getUseBoxes().get(cswpi.getIndex()).getValue()));
			// }
		}
		return values;
	}

	private String retrieveConstantFromValue(Value val) {
		if (val instanceof StringConstant) {
			return ((StringConstant) val).value;
		} else if (val instanceof IntConstant || val.getType() instanceof IntType) {
			return val.toString();
		} else {
			return "";
		}
	}

	private final static List<String> trackedTypes = Arrays.asList("java.lang.String", "int", "java.lang.Integer");

	private boolean isOfNonTrackableType(String varName) {
		for (Entry<String, String> object : spec.getRule().getObjects()) {
			if (object.getValue().equals(varName) && trackedTypes.contains(object.getKey())) {
				return false;
			}
		}
		return true;
	}

	public ClassSpecification getSpec() {
		return spec;
	}

	private boolean isPotentiallyPredicateGeneratingState(CryptSLPredicate ensPred, State stateNode) {
		return ensPred instanceof CryptSLCondPredicate
				&& isConditionalState(((CryptSLCondPredicate) ensPred).getConditionalMethods(), stateNode)
				|| (!(ensPred instanceof CryptSLCondPredicate) && stateNode.isAccepting());
	}

	private boolean isConditionalState(Set<StateNode> conditionalMethods, State state) {
		if (conditionalMethods == null)
			return false;
		for (StateNode s : conditionalMethods) {
			if (new WrappedState(s).equals(state)) {
				return true;
			}
		}
		return false;
	}


	public ExtractParameterAnalysis getParameterAnalysis() {
		return parameterAnalysis;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + ((spec == null) ? 0 : spec.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		AnalysisSeedWithSpecification other = (AnalysisSeedWithSpecification) obj;
		if (spec == null) {
			if (other.spec != null)
				return false;
		} else if (!spec.equals(other.spec))
			return false;
		return true;
	}

	public boolean isSecure() {
		return secure;
	}

	public void setSecure(boolean secure) {
		this.secure = secure;
	}

	public Map<Statement, SootMethod> getAllCallsOnObject() {
		return allCallsOnObject;
	}
	
	public boolean reaches(Node<Statement, Val> node) {
		return results != null && results.asStatementValWeightTable().row(node.stmt()).keySet().contains(node.fact());
	}


	public void evaluateInternalConstraints() {
		constraintSolver = new ConstraintSolver(this, cryptoScanner.getAnalysisListener());
		internalConstraintSatisfied = (constraintSolver.evaluateRelConstraints() == 0);
	}

	@Override
	public void addRequiredPredicate(ForwardQuery requiringObjectAllocation,
			RequiredCryptSLPredicate requiredCryptSLPredicate) {
		//Remove conditional Predicates straight away.
		final ISLConstraint conditional = requiredCryptSLPredicate.getPred().getConstraint();
		if (conditional != null) {
			EvaluableConstraint evalCons = constraintSolver.createConstraint(conditional);
			evalCons.evaluate();
			if (evalCons.hasErrors()) {
				return;
			}
		}
		super.addRequiredPredicate(requiringObjectAllocation, requiredCryptSLPredicate);
	}
	@Override
	public boolean hasEnsuredPredicate(Statement value) {
		return internalConstraintSatisfied && super.hasEnsuredPredicate(value);
	}

}
