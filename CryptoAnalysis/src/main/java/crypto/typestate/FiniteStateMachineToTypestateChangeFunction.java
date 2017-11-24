package crypto.typestate;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import com.beust.jcommander.internal.Lists;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import boomerang.jimple.AllocVal;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import crypto.rules.CryptSLMethod;
import crypto.rules.StateMachineGraph;
import crypto.rules.StateNode;
import crypto.rules.TransitionEdge;
import ideal.IDEALAnalysisDefinition;
import soot.RefType;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.AssignStmt;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.NewExpr;
import soot.jimple.Stmt;
import sync.pds.solver.nodes.Node;
import typestate.TransitionFunction;
import typestate.finiteautomata.ITransition;
import typestate.finiteautomata.MatcherTransition;
import typestate.finiteautomata.MatcherTransition.Parameter;
import typestate.finiteautomata.MatcherTransition.Type;
import typestate.finiteautomata.State;
import typestate.finiteautomata.TypeStateMachineWeightFunctions;

public class FiniteStateMachineToTypestateChangeFunction extends TypeStateMachineWeightFunctions {

	private Collection<SootMethod> initialTransitonLabel;
	private Collection<SootMethod> edgeLabelMethods = Sets.newHashSet();
	private Collection<SootMethod> methodsInvokedOnInstance = Sets.newHashSet();
	
	private final StateMachineGraph stateMachineGraph;
	private Multimap<State, SootMethod> outTransitions = HashMultimap.create();
	private Collection<RefType> analyzedType = Sets.newHashSet();
	private ExtendedIDEALAnaylsis solver;

	public FiniteStateMachineToTypestateChangeFunction(StateMachineGraph fsm, ExtendedIDEALAnaylsis solver) {
		this.stateMachineGraph = fsm;
		this.solver = solver;
		this.initialTransitonLabel = convert(stateMachineGraph.getInitialTransition().getLabel());
		//TODO #15 we must start the analysis in state stateMachineGraph.getInitialTransition().from();
		WrappedState initialState = new WrappedState(stateMachineGraph.getInitialTransition().to());
		for (final TransitionEdge t : stateMachineGraph.getAllTransitions()) {
			WrappedState from = new WrappedState(t.from());
			WrappedState to = new WrappedState(t.to());
			this.addTransition(new LabeledMatcherTransition(from, t.getLabel(),
					Parameter.This, to, Type.OnCallToReturn));
			outTransitions.putAll(from, convert(t.getLabel()));
		}
		if(startAtConstructor()){
			List<SootMethod> label = Lists.newLinkedList();
			for(SootMethod m : initialTransitonLabel)
				if(m.isConstructor())
					label.add(m);
			this.addTransition(new MatcherTransition(initialState, label, Parameter.This, initialState, Type.OnCallToReturn));
			this.outTransitions.putAll(initialState, label);
		}
		//All transitions that are not in the state machine 
		for(StateNode t :  this.stateMachineGraph.getNodes()){
			State wrapped = new WrappedState(t);
			Collection<SootMethod> remaining = getInvolvedMethods();
			Collection<SootMethod> outs =  this.outTransitions.get(wrapped);
			if(outs == null)
				outs = Sets.newHashSet();
			remaining.removeAll(outs);
			this.addTransition(new MatcherTransition(wrapped, remaining, Parameter.This, ErrorStateNode.v(), Type.OnCallToReturn));
		}
	}

	private boolean startAtConstructor() {
		for(SootMethod m : initialTransitonLabel){
			if(m.isConstructor()){
				analyzedType.add(m.getDeclaringClass().getType());
			}
		}
		return !analyzedType.isEmpty();
	}

	private Collection<SootMethod> convert(List<CryptSLMethod> label) {
		Collection<SootMethod> converted = CryptSLMethodToSootMethod.v().convert(label);
		edgeLabelMethods.addAll(converted);
		return converted;
	}


	private Collection<SootMethod> convert(CryptSLMethod label) {
		Collection<SootMethod> converted = CryptSLMethodToSootMethod.v().convert(label);
		edgeLabelMethods.addAll(converted);
		return converted;
	}

	@Override
	public TransitionFunction normal(Node<Statement, Val> curr, Node<Statement, Val> succ) {
		TransitionFunction val = super.normal(curr, succ);
		if(curr.stmt().getUnit().get() instanceof Stmt){
			Stmt callSite = (Stmt) curr.stmt().getUnit().get();
			if(callSite.containsInvokeExpr()){
				for (ITransition t : val.values()) {
					if (!(t instanceof LabeledMatcherTransition))
						continue;
					injectQueryAtCallSite(((LabeledMatcherTransition)t).label,callSite);
				}		
				if(callSite.getInvokeExpr() instanceof InstanceInvokeExpr){
					InstanceInvokeExpr e = (InstanceInvokeExpr)callSite.getInvokeExpr();
					if(e.getBase().equals(curr.fact().value())){
						solver.methodInvokedOnInstance(callSite);
					}
				}
			}
		}
		return val;
	}
	public void injectQueryForSeed(Unit u){
        injectQueryAtCallSite(stateMachineGraph.getInitialTransition().getLabel(),u);
	}
	
	private void injectQueryAtCallSite(List<CryptSLMethod> list, Unit callSite) {
		for(CryptSLMethod matchingDescriptor : list){
			for(SootMethod m : convert(matchingDescriptor)){
				if (!(callSite instanceof Stmt))
					continue;
				Stmt stmt = (Stmt) callSite;
				if (!stmt.containsInvokeExpr())
					continue;
				SootMethod method = stmt.getInvokeExpr().getMethod();
				if (!m.equals(method))
					continue;
				{
					int index = 0;
					for(Entry<String, String> param : matchingDescriptor.getParameters()){
						if(!param.getKey().equals("_")){
							soot.Type parameterType = method.getParameterType(index);
							if(parameterType.toString().equals(param.getValue())){
								solver.addQueryAtCallsite(param.getKey(), stmt, index);
							}
						}
						index++;
					}
				}
			}
		}
	}

	@Override
	public Collection<Val> generateSeed(SootMethod method, Unit unit, Collection<SootMethod> optional) {
		Set<Val> out = new HashSet<>();
		if(startAtConstructor()){
			if(unit instanceof AssignStmt){
				AssignStmt as = (AssignStmt) unit;
				if(as.getRightOp() instanceof NewExpr){
					NewExpr newExpr = (NewExpr) as.getRightOp();
					if(analyzedType.contains(newExpr.getType())){
						AssignStmt stmt = (AssignStmt) unit;
						out.add(new AllocVal(stmt.getLeftOp(), method, as.getRightOp()));
					}
				}
			}
		}
		if (!(unit instanceof Stmt) || !((Stmt) unit).containsInvokeExpr())
			return out;
		InvokeExpr invokeExpr = ((Stmt) unit).getInvokeExpr();
		SootMethod calledMethod = invokeExpr.getMethod();
		if (!initialTransitonLabel.contains(calledMethod) || calledMethod.isConstructor())
			return out;
		if (calledMethod.isStatic()) {
			if(unit instanceof AssignStmt){
				AssignStmt stmt = (AssignStmt) unit;
				out.add(new AllocVal(stmt.getLeftOp(), method, stmt.getRightOp()));
			}
		} else if (invokeExpr instanceof InstanceInvokeExpr){
			InstanceInvokeExpr iie = (InstanceInvokeExpr) invokeExpr;
			out.add(new Val(iie.getBase(), method));
		}
		return out;
	}

	
	public Collection<SootMethod> getInvolvedMethods(){
		return Sets.newHashSet(edgeLabelMethods);
	}

	public Collection<SootMethod> getAllMethodsInvokedOnInstance(){
		return Sets.newHashSet(methodsInvokedOnInstance);
	}
	
	public Collection<SootMethod> getEdgesOutOf(State n){
		return outTransitions.get(n);
	}
	
	private class LabeledMatcherTransition extends MatcherTransition{

		private final List<CryptSLMethod> label;

		public LabeledMatcherTransition(State from, List<CryptSLMethod> label,
				Parameter param, State to,
				Type type) {
			super(from,convert(label), param, to, type);
			this.label = label;
		}}
	
	private class WrappedState implements State{
		private final StateNode delegate;
		private final boolean initialState;

		WrappedState(StateNode delegate){
			this.delegate = delegate;
			this.initialState = stateMachineGraph.getInitialTransition().to().equals(delegate);
		}
		@Override
		public boolean isErrorState() {
			return delegate.isErrorState();
		}

		@Override
		public boolean isAccepting() {
			return delegate.getAccepting();
		}
		@Override
		public boolean isInitialState() {
			return initialState;
		}
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((delegate == null) ? 0 : delegate.hashCode());
			return result;
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			WrappedState other = (WrappedState) obj;
			if (delegate == null) {
				if (other.delegate != null)
					return false;
			} else if (!delegate.equals(other.delegate))
				return false;
			return true;
		}
		
		@Override
		public String toString() {
			return delegate.getName().toString();
		}
	}
}
