package crypto.analysis;

import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;

import boomerang.debugger.Debugger;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import boomerang.results.ForwardBoomerangResults;
import crypto.rules.CryptSLPredicate;
import crypto.rules.StateMachineGraph;
import crypto.rules.StateNode;
import crypto.rules.TransitionEdge;
import crypto.typestate.ExtendedIDEALAnaylsis;
import crypto.typestate.SootBasedStateMachineGraph;
import ideal.IDEALSeedSolver;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;
import sync.pds.solver.nodes.Node;
import typestate.TransitionFunction;
import wpds.impl.Weight;

public class AnalysisSeedWithEnsuredPredicate extends IAnalysisSeed{

	
	private ExtendedIDEALAnaylsis problem;

	public AnalysisSeedWithEnsuredPredicate(CryptoScanner cryptoScanner, Node<Statement,Val> delegate) {
		super(cryptoScanner,delegate.stmt(),delegate.fact(), TransitionFunction.one());
	}

	public AnalysisSeedWithEnsuredPredicate(CryptoScanner cryptoScanner, Node<Statement,Val> delegate, Table<Statement, Val, ? extends Weight> results) {
		super(cryptoScanner,delegate.stmt(),delegate.fact(), TransitionFunction.one());
		this.analysisResults = results;
	}
	
	@Override
	public void execute() {
		cryptoScanner.getAnalysisListener().seedStarted(this);
		ExtendedIDEALAnaylsis solver = getOrCreateAnalysis();
		solver.run(this);
		analysisResults = solver.getResults().asStatementValWeightTable();

		if(analysisResults == null)
			return;

		addPotentialPredicates();
		
	}

	private ExtendedIDEALAnaylsis getOrCreateAnalysis() {
		problem = new ExtendedIDEALAnaylsis() {
			
			@Override
			protected BiDiInterproceduralCFG<Unit, SootMethod> icfg() {
				return cryptoScanner.icfg();
			}
			
			@Override
			public SootBasedStateMachineGraph getStateMachine() {
				StateMachineGraph m = new StateMachineGraph();
				StateNode s = new StateNode("0", true, true){
					@Override
					public String toString() {
						return "";
					}
				};
				m.addNode(s);
				m.addEdge(new TransitionEdge(Lists.newLinkedList(), s,s));
				return new SootBasedStateMachineGraph(m);
			}
			
			@Override
			public CrySLResultsReporter analysisListener() {
				return cryptoScanner.getAnalysisListener();
			}
			

			@Override
			protected Debugger<TransitionFunction> debugger(IDEALSeedSolver<TransitionFunction> solver) {
				return cryptoScanner.debugger(solver,AnalysisSeedWithEnsuredPredicate.this);
			}
		};
		return problem;
	}

	@Override
	public String toString() {
		return "AnalysisSeedWithEnsuredPredicate:"+this.asNode() +" " + ensuredPredicates; 
	}

	public boolean reaches(Node<Statement, Val> node) {
		return analysisResults != null && analysisResults.row(node.stmt()).containsKey(node.fact());
	}
}
