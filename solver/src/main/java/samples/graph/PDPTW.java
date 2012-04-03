/**
 *  Copyright (c) 1999-2011, Ecole des Mines de Nantes
 *  All rights reserved.
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 *      * Redistributions of source code must retain the above copyright
 *        notice, this list of conditions and the following disclaimer.
 *      * Redistributions in binary form must reproduce the above copyright
 *        notice, this list of conditions and the following disclaimer in the
 *        documentation and/or other materials provided with the distribution.
 *      * Neither the name of the Ecole des Mines de Nantes nor the
 *        names of its contributors may be used to endorse or promote products
 *        derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS ``AS IS'' AND ANY
 *  EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL THE REGENTS AND CONTRIBUTORS BE LIABLE FOR ANY
 *  DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package samples.graph;

import choco.kernel.ResolutionPolicy;
import gnu.trove.list.array.TIntArrayList;
import solver.Solver;
import solver.constraints.ConstraintFactory;
import solver.constraints.gary.GraphConstraint;
import solver.constraints.gary.GraphConstraintFactory;
import solver.constraints.propagators.gary.constraintSpecific.PropAllDiffGraphIncremental;
import solver.constraints.propagators.gary.vrp.*;
import solver.propagation.generator.Primitive;
import solver.propagation.generator.Sort;
import solver.search.loop.monitors.SearchMonitorFactory;
import solver.search.strategy.assignments.Assignment;
import solver.search.strategy.decision.Decision;
import solver.search.strategy.decision.graph.GraphDecision;
import solver.search.strategy.strategy.graph.GraphStrategy;
import solver.variables.IntVar;
import solver.variables.VariableFactory;
import solver.variables.graph.GraphType;
import solver.variables.graph.GraphVar;
import solver.variables.graph.INeighbors;
import solver.variables.graph.directedGraph.DirectedGraphVar;

import java.io.*;

/**
 * Parse and solve an Li and Lim PDPTW instances
 */
public class PDPTW {

	//***********************************************************************************
	// VARIABLES
	//***********************************************************************************

	private static final long TIMELIMIT = 10000;
	// instance
	private static String instanceName;
	private static int[][] distanceMatrix;
	private static int n, noVal;
	// model
	private static Solver solver;
	private static DirectedGraphVar graph;
	private static IntVar[] time,truck;
	private static IntVar length;
	private static int nbTrucks;
	private static int nbMaxTrucks;
	private static int capacity;
	private static GraphConstraint gc;
	private static int[] open,close,dur,demand;
	private static TIntArrayList precedFrom,precedTo;
	private final static int FLOOR = 0;
	private final static int ROUND = 1;
	private final static int CEIL = 2;
	private final static int ROUNDING = FLOOR;

	//***********************************************************************************
	// MODEL-SEARCH-RESOLUTION-OUTPUT
	//***********************************************************************************

	public static void solve() {
		buildModel();
		addConstraints();
		configureAndSolve();
	}

	private static void buildModel() {
		solver = new Solver();
		graph = new DirectedGraphVar(solver, n, GraphType.LINKED_LIST, GraphType.LINKED_LIST);
		length = VariableFactory.bounded("total cost ", 0,1000000, solver);
		time = new IntVar[n];
		truck = new IntVar[n];
		for(int i=0;i<n;i++){
			if(i<2*nbTrucks && i%2==0){
				time[i] = VariableFactory.bounded("time "+i,open[i],open[i],solver);
			}else{
				time[i] = VariableFactory.bounded("time "+i,open[i],close[i],solver);
			}
		}
		for (int i = 0; i < n; i++) {
			graph.getKernelGraph().activateNode(i);
			for (int j = 0; j < n; j++) {
				if (i!=j && distanceMatrix[i][j]!=noVal	&& open[i]+distanceMatrix[i][j]<=close[j]) {
					graph.getEnvelopGraph().addArc(i, j);
				}
			}
		}
		for(int i=0;i<2*nbTrucks;i+=2){
			truck[i] = truck[i+1] = VariableFactory.bounded("t" + i, i / 2, i / 2, solver);
		}
		for(int i=0;i<precedFrom.size();i++){
			truck[precedFrom.get(i)] = truck[precedTo.get(i)] = VariableFactory.enumerated("t"+i,0,nbTrucks-1,solver);
			if(demand[precedFrom.get(i)]+demand[precedTo.get(i)]!=0){
				throw new UnsupportedOperationException("case not handled");
			}
		}
		for(int i=0;i<n;i++){
			if(truck[i]==null){
				throw new UnsupportedOperationException("node "+i+" has no truck variable");
			}
		}
	}

	private static void addConstraints(){
		// basic model
		gc = GraphConstraintFactory.makeConstraint(graph, solver);
		gc.addAdHocProp(new Prop1Succ_butEndingDepot(graph, nbTrucks, gc, solver));
		gc.addAdHocProp(new Prop1Pred_butStartingDepot(graph, nbTrucks, gc, solver));
		gc.addAdHocProp(new PropNoCircuit(graph, gc, solver));// could be removed
		gc.addAdHocProp(new PropTruck_Capacity_NoCircuit(graph,demand,capacity,nbTrucks,gc,solver));
		gc.addAdHocProp(new PropTruckCompatibility(graph,truck,nbTrucks,gc,solver));

		// structral redundant filtering
		// --- AllDiff GAC (efficient)
		gc.addAdHocProp(new PropAllDiffGraphIncremental(graph,n-nbTrucks,solver,gc));
		// --- Reachability
		gc.addAdHocProp(new PropTruckReachability(graph,truck,nbTrucks,gc,solver));
		// --- ArboAntiArbo GAC (slow)
		gc.addAdHocProp(new PropArborescences(graph,gc,solver,true));
		gc.addAdHocProp(new PropAntiArborescences(graph,gc,solver,false));

		// costs
		gc.addAdHocProp(new PropSumArcCosts(graph, length, distanceMatrix, gc, solver));

		// time
		gc.addAdHocProp(new PropTimeGraphChanneling(time,graph, distanceMatrix, gc, solver));
		gc.addAdHocProp(new PropGraphTimeChanneling(time,truck,graph, distanceMatrix,nbTrucks, gc, solver));
		gc.addAdHocProp(new PropGlobalPrecedences(time,graph, distanceMatrix,precedFrom,precedTo, gc, solver));
		gc.addAdHocProp(new PropEnergeticTime(time,truck,graph, distanceMatrix,nbTrucks,close[1], gc, solver));
		// precedences
		for(int i=0;i<precedFrom.size();i++){
			solver.post(ConstraintFactory.gt(time[precedTo.get(i)], time[precedFrom.get(i)], solver));
		}
		// cumulatif-geost ???

		// truck + time
		gc.addAdHocProp(new PropTruckTimeChanneling(time,truck,graph, distanceMatrix,nbTrucks, gc, solver));

		solver.post(gc);
	}

	private static void configureAndSolve() {
//		solver.set(StrategyFactory.graphLexico(graph));
		solver.set(new Earliest(graph));
//		solver.set(new MilleFeuille(graph));
//		solver.set(new MinDeg(graph));
//		solver.set(new BestFit(graph));
//		solver.set(new StrategiesSequencer(solver.getEnvironment(),StrategyFactory.random(truck,solver.getEnvironment()),new BestFit(graph)));
		solver.set(Sort.build(Primitive.arcs(gc)).clearOut());
		solver.getSearchLoop().getLimitsBox().setTimeLimit(TIMELIMIT);
//		solver.getSearchLoop().getLimitsBox().setFailLimit(1);
		SearchMonitorFactory.log(solver, true, false);
//		solver.findAllSolutions();
		solver.findOptimalSolution(ResolutionPolicy.MINIMIZE,length);
		System.out.println(length);
//		System.out.println(graph.getEnvelopGraph());
	}

	//***********************************************************************************
	// BENCHMARK
	//***********************************************************************************

	public static void main(String[] args) {
//		bench();
		test();
	}

	private static void bench() {
		String dir = "/Users/jfages07/github/In4Ga/pdp_100";
		File folder = new File(dir);
		String[] list = folder.list();
		for (String s : list) {
			if (s.contains(".txt") && s.contains("lc2") && !s.contains("000")){
				System.out.println("parsing instance "+s+".../n");
				loadInstance(dir + "/" + s, 1);
				instanceName = s;
				if(n%2!=1){
					for(int i=1;i<nbMaxTrucks;i++){
						loadInstance(dir+"/"+s, i);
						solve();
						if(solver.getMeasures().getSolutionCount()>0){
							System.out.println(s+" SOLVED with "+i+" trucks (cost="+length+")");
							break;
						}else if(solver.getMeasures().getTimeCount()>TIMELIMIT){
							System.out.println(s+" UNDEFINED for "+i+" trucks");
						}else{
							System.out.println("no sol with "+i+" trucks");
						}
					}
				}else{
					System.out.println("CANNOT HANDLE INSTANCE : "+s);
				}
			}
		}
	}

	private static void test() {
		String dir = "/Users/jfages07/github/In4Ga/pdp_100";
		File folder = new File(dir);
		String[] list = folder.list();
		for (String s : list) {
			if (s.contains("lc201.txt")){
				System.out.println("parsing instance "+s+".../n");
				instanceName = s;
				loadInstance(dir+"/"+s, 3);
				solve();
				if(solver.getMeasures().getSolutionCount()>0){
					System.out.println(s+" SOLVED (cost="+length+")");
				}else if(solver.getMeasures().getTimeCount()>TIMELIMIT){
					System.out.println(s+" UNDEFINED");
				}
			}
		}
	}

	private static void loadInstance(String url, int nb){
		File file = new File(url);
		try {
			BufferedReader buf = new BufferedReader(new FileReader(file));
			String[] lineNumbers;
			String line = buf.readLine();
			nbTrucks = nb;
			n = 2*nb-2;
			while(line!=null && line!=""){
				n++; line = buf.readLine();
			}
			buf = new BufferedReader(new FileReader(file));
			line = buf.readLine();
			lineNumbers = line.split("\t");
			precedFrom = new TIntArrayList();
			precedTo   = new TIntArrayList();
			nbMaxTrucks = Integer.parseInt(lineNumbers[0]);
			capacity = Integer.parseInt(lineNumbers[1]);
			open = new int[n];
			close = new int[n];
			demand = new int[n];
			dur = new int[n];
			int[] x = new int[n];
			int[] y = new int[n];
			distanceMatrix = new int[n][n];
			int f,t;
			//depot
			line = buf.readLine();
			lineNumbers = line.split("\t");
			for(int i=0;i<2*nbTrucks;i++){
				x[i] = Integer.parseInt(lineNumbers[1]);
				y[i] = Integer.parseInt(lineNumbers[2]);
				demand[i] = Integer.parseInt(lineNumbers[3]);
				open[i] = Integer.parseInt(lineNumbers[4]);
				close[i] = Integer.parseInt(lineNumbers[5]);
				dur[i] = Integer.parseInt(lineNumbers[6]);
			}
			//customers
			for(int i=2*nbTrucks;i<n;i++){
				line = buf.readLine();
				lineNumbers = line.split("\t");
				x[i] = Integer.parseInt(lineNumbers[1]);
				y[i] = Integer.parseInt(lineNumbers[2]);
				demand[i] = Integer.parseInt(lineNumbers[3]);
				open[i] = Integer.parseInt(lineNumbers[4]);
				close[i] = Integer.parseInt(lineNumbers[5]);
				dur[i] = Integer.parseInt(lineNumbers[6]);
				f = Integer.parseInt(lineNumbers[7]);
				t = Integer.parseInt(lineNumbers[8]);
				if(f==0 && t==0){
					throw new UnsupportedOperationException("case not handled");
				}else if(f!=0){
					precedFrom.add(f+2*nbTrucks-1);
					precedTo.add(i);
				}
			}
			double d;
			for(int i=0;i<n;i++){
				for(int j=i+1;j<n;j++){
					d = (x[i]-x[j])*(x[i]-x[j]);
					d+= (y[i]-y[j])*(y[i]-y[j]);
					d = Math.sqrt(d);
					switch (ROUNDING){
						case FLOOR: d = Math.floor(d);break;
						case ROUND: Math.round(d);break;
						case CEIL: Math.ceil(d);break;
					}
					distanceMatrix[i][j] = dur[i]+(int)d;
					distanceMatrix[j][i] = dur[j]+(int)d;
				}
			}
			noVal = Integer.MAX_VALUE;
			for(int i=0;i<2*nbTrucks;i++){
				for(int j=0;j<2*nbTrucks;j++){
					distanceMatrix[i][j] = noVal;
				}
			}
			for(int i=0;i<n;i++){
				for(int j=0;j<2*nbTrucks;j+=2){
					distanceMatrix[i][j] = distanceMatrix[j+1][i] = noVal;
				}
				distanceMatrix[i][i]   = noVal;
			}
			for(int i=0;i<precedFrom.size();i++){
				for(t=0;t<2*nbTrucks;t+=2){
					distanceMatrix[t][precedTo.get(i)] = distanceMatrix[precedFrom.get(i)][t+1] = noVal;
				}
				distanceMatrix[precedTo.get(i)][precedFrom.get(i)] = noVal;
			}
		}catch(Exception e){
			e.printStackTrace();
			System.exit(0);
		}
	}

	//***********************************************************************************
	// RECORDING RESULTS
	//***********************************************************************************

	public static void writeTextInto(String text, String file) {
		try {
			FileWriter out = new FileWriter(file, true);
			out.write(text);
			out.flush();
			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void clearFile(String file) {
		try {
			FileWriter out = new FileWriter(file, false);
			out.write("");
			out.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	//***********************************************************************************
	// SEARCH HEURISTICS
	//***********************************************************************************

	private static class Earliest extends GraphStrategy{

		public Earliest(GraphVar graphVar) {
			super(graphVar);
		}

		@Override
		public Decision getDecision() {
			int fromTo = nextArc();
			if(fromTo == -1){
				return null;
			}
			return new GraphDecision(g, fromTo, Assignment.graph_enforcer);
		}

		public int nextArc() {
			INeighbors nei;
			for(int i=0;i<n;i++){
				nei = g.getEnvelopGraph().getSuccessorsOf(i);
				if(nei.neighborhoodSize()>1){
					int min = length.getUB()+1;
					int d;
					int next = -1;
					for(int j=nei.getFirstElement();j>=0;j=nei.getNextElement()){
						d = Math.max(time[j].getLB(),time[i].getLB()+distanceMatrix[i][j]);
						if(d<min){
							min = d;
							next = j;
						}
					}
					if(next == -1)throw new UnsupportedOperationException();
					return (i+1)*n+next;
				}
			}
			return -1;
		}
	}

	private static class MilleFeuille extends GraphStrategy{

		private int idx;

		public MilleFeuille(GraphVar graphVar) {
			super(graphVar);
			idx = -2;
		}

		@Override
		public Decision getDecision() {
			int fromTo;
			for(int i=idx+2;i<2*nbTrucks;i+=2){
				fromTo = earliest(i);
				if(fromTo!=-1){
					idx = i;
					return new GraphDecision(g, fromTo, Assignment.graph_enforcer);
				}
			}
			for(int i=0;i<=idx;i+=2){
				fromTo = earliest(i);
				if(fromTo!=-1){
					idx = i;
					return new GraphDecision(g, fromTo, Assignment.graph_enforcer);
				}
			}
			if(!g.instantiated()){
				throw new UnsupportedOperationException();
			}
			return null;
		}

		public int earliest(int tr) {
			int x = tr;
			int y = g.getKernelGraph().getSuccessorsOf(x).getFirstElement();
			while(y>=2*nbTrucks){
				x = y;
				y = g.getKernelGraph().getSuccessorsOf(x).getFirstElement();
			}
			if(y!=-1){
				return -1;
			}
			INeighbors nei = g.getEnvelopGraph().getSuccessorsOf(x);
			if(nei.neighborhoodSize()>1){
				int min = length.getUB()+1;
				int d;
				int next = -1;
				for(int j=nei.getFirstElement();j>=0;j=nei.getNextElement()){
					d = Math.max(time[j].getLB(),time[x].getLB()+distanceMatrix[x][j]);
					if(j>=2*nbTrucks && d<min){
						min = d;
						next = j;
					}
				}
				if(next == -1)throw new UnsupportedOperationException();
//				System.out.println(x+" - "+next);
				return (x+1)*n+next;
			}else{
				System.out.println(x+" : "+nei.neighborhoodSize());
				throw new UnsupportedOperationException();
			}
		}
		public int smallestUB(int tr) {
			int x = tr;
			int y = g.getKernelGraph().getSuccessorsOf(x).getFirstElement();
			while(y>=2*nbTrucks){
				x = y;
				y = g.getKernelGraph().getSuccessorsOf(x).getFirstElement();
			}
			if(y!=-1){
				return -1;
			}
			INeighbors nei = g.getEnvelopGraph().getSuccessorsOf(x);
			if(nei.neighborhoodSize()>1){
				int min = length.getUB()+1;
				int next = -1;
				for(int j=nei.getFirstElement();j>=0;j=nei.getNextElement()){
					if(time[j].getUB()<min){
						min = time[j].getUB();
						next = j;
					}
				}
				if(next == -1)throw new UnsupportedOperationException();
				return (x+1)*n+next;
			}else{
				System.out.println(x+" : "+nei.neighborhoodSize());
				throw new UnsupportedOperationException();
			}
		}
		public int closest(int tr) {
			int x = tr;
			int y = g.getKernelGraph().getSuccessorsOf(x).getFirstElement();
			while(y>=2*nbTrucks){
				x = y;
				y = g.getKernelGraph().getSuccessorsOf(x).getFirstElement();
			}
			if(y!=-1){
				return -1;
			}
			INeighbors nei = g.getEnvelopGraph().getSuccessorsOf(x);
			if(nei.neighborhoodSize()>1){
				int min = length.getUB()+1;
				int next = -1;
				for(int j=nei.getFirstElement();j>=0;j=nei.getNextElement()){
					if(j>=2*nbTrucks && distanceMatrix[x][j]<min){
						min = distanceMatrix[x][j];
						next = j;
					}
				}
				if(next == -1)throw new UnsupportedOperationException();
				return (x+1)*n+next;
			}else{
				throw new UnsupportedOperationException();
			}
		}
	}

	private static class MinDeg extends GraphStrategy{

		public MinDeg(GraphVar graphVar) {
			super(graphVar);
		}

		@Override
		public Decision getDecision() {
			int size = n*2;
			int next = -1;
			int ss;
			INeighbors nei;
			for(int i=0;i<n;i++){
				nei = g.getEnvelopGraph().getSuccessorsOf(i);
				ss  = nei.neighborhoodSize();
				for(int j=nei.getFirstElement();j>=0;j=nei.getNextElement()){
					if(!g.getKernelGraph().arcExists(i,j)){
						if(ss+g.getEnvelopGraph().getPredecessorsOf(j).neighborhoodSize()<size){
							size = ss+g.getEnvelopGraph().getPredecessorsOf(j).neighborhoodSize();
							next = (i+1)*n+j;
						}
					}
				}
			}
			if(next!=-1){
				return new GraphDecision(g, next, Assignment.graph_enforcer);
			}
			if(!g.instantiated()){
				throw new UnsupportedOperationException();
			}
			return null;
		}
	}

	private static class BestFit extends GraphStrategy{

		public BestFit(GraphVar graphVar) {
			super(graphVar);
		}

		@Override
		public Decision getDecision() {
			int fromTo = getBestTruck();
			if(fromTo!=-1){
//				System.out.println((fromTo/n-1)+" -> "+(fromTo%n));
				return new GraphDecision(g, fromTo, Assignment.graph_enforcer);
			}
			if(!g.instantiated()){
				throw new UnsupportedOperationException();
			}
			return null;
		}

		private int getBestTruck() {
			int bestNode = -1;
			int bestTime = length.getUB()+1;
			for(int i=0;i<2*nbTrucks;i+=2){
				int x = i;
				int y = g.getKernelGraph().getSuccessorsOf(x).getFirstElement();
				while(y>=2*nbTrucks){
					x = y;
					y = g.getKernelGraph().getSuccessorsOf(x).getFirstElement();
				}
				if(y==-1){
					if(!time[x].instantiated()){
						throw new UnsupportedOperationException();
					}
					if(time[x].getLB()<bestTime){
						bestTime = time[x].getLB();
						bestNode = x;
					}
				}
			}
			if(bestNode==-1){
				return -1;
			}
			int min = length.getUB()+1;
			int d;
			int next = -1;
			INeighbors nei = g.getEnvelopGraph().getSuccessorsOf(bestNode);
			for(int j=nei.getFirstElement();j>=0;j=nei.getNextElement()){
				d = Math.max(time[j].getLB(),time[bestNode].getLB()+distanceMatrix[bestNode][j]);
				if(d<min){
					min = d;
					next = j;
				}
			}
			return (bestNode+1)*n+next;
		}
	}
}