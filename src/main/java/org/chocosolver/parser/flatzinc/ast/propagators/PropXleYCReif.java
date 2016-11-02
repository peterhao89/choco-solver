/**
 * Copyright (c) 2014, chocoteam
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the {organization} nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.chocosolver.parser.flatzinc.ast.propagators;

import org.chocosolver.solver.constraints.Propagator;
import org.chocosolver.solver.constraints.PropagatorPriority;
import org.chocosolver.solver.exception.ContradictionException;
import org.chocosolver.solver.explanations.RuleStore;
import org.chocosolver.solver.variables.BoolVar;
import org.chocosolver.solver.variables.IntVar;
import org.chocosolver.solver.variables.events.IEventType;
import org.chocosolver.solver.variables.events.IntEventType;
import org.chocosolver.util.ESat;

/**
 * (X <= Y + C) <=> r
 * <p>
 * Project: choco-parsers.
 *
 * @author Charles Prud'homme
 * @since 03/05/2016.
 */
public class PropXleYCReif extends Propagator<IntVar> {

    int cste;

    public PropXleYCReif(IntVar x, IntVar y, int c, BoolVar r) {
        super(new IntVar[]{x, y, r}, PropagatorPriority.TERNARY, false);
        this.cste = c;
    }

    @Override
    public void propagate(int evtmask) throws ContradictionException {
        if (vars[2].getLB() == 1) {
            vars[0].updateUpperBound(vars[1].getUB() + cste, this);
            vars[1].updateLowerBound(vars[0].getLB() - cste, this);
            if (vars[0].getUB() <= vars[1].getLB() + cste) {
                this.setPassive();
            }
        } else {
            if (vars[2].getUB() == 0) {
                vars[0].updateLowerBound(vars[1].getLB()  + cste + 1, this);
                vars[1].updateUpperBound(vars[0].getUB() - cste - 1, this);
                if (vars[0].getLB() > vars[1].getUB() + cste) {
                    setPassive();
                }
            } else {
                if (vars[0].getUB() <= vars[1].getLB() + cste) {
                    setPassive();
                    vars[2].instantiateTo(1, this);
                } else if (vars[0].getLB() > vars[1].getUB() + cste) {
                    setPassive();
                    vars[2].instantiateTo(0, this);
                }
            }
        }
    }

    @Override
    public ESat isEntailed() {
        throw new UnsupportedOperationException("isEntailed not implemented ");
    }

    @Override
    public boolean why(RuleStore ruleStore, IntVar var, IEventType evt, int value) {
        boolean nrules = ruleStore.addPropagatorActivationRule(this);
        if (var == vars[2]) {
            if (vars[2].isInstantiatedTo(1)) {
                nrules |= ruleStore.addUpperBoundRule(vars[0]);
                nrules |= ruleStore.addLowerBoundRule(vars[1]);
            } else {
                nrules |= ruleStore.addLowerBoundRule(vars[0]);
                nrules |= ruleStore.addUpperBoundRule(vars[1]);
            }
        } else {
            if (var == vars[0]) {
                if (evt == IntEventType.DECUPP) {
                    nrules |= ruleStore.addUpperBoundRule(vars[1]);
                } else {
                    nrules |= ruleStore.addLowerBoundRule(vars[1]);
                }
            } else if (var == vars[1]) {
                if (evt == IntEventType.DECUPP) {
                    nrules |= ruleStore.addUpperBoundRule(vars[0]);
                } else {
                    nrules |= ruleStore.addLowerBoundRule(vars[0]);
                }
            }
            nrules |= ruleStore.addFullDomainRule(vars[2]);
        }
        return nrules;
    }
}
