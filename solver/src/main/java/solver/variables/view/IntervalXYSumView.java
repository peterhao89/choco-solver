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
package solver.variables.view;

import solver.Cause;
import solver.ICause;
import solver.Solver;
import solver.exception.ContradictionException;
import solver.variables.AbstractVariable;
import solver.variables.EventType;
import solver.variables.IntVar;

import static solver.variables.AbstractVariable.*;

/**
 * View for A+B, where A and B are IntVar or views, ensure bound consistency
 * based on "Bounds Consistency Techniques for Long Linear Constraint"
 * <br/>
 *
 * @author Charles Prud'homme
 * @since 23/08/11
 */
public final class IntervalXYSumView extends AbstractSumView {

    public IntervalXYSumView(IntVar a, IntVar b, Solver solver) {
        super(a, b, solver);
    }

    /////////////// SERVICES REQUIRED FROM INTVAR //////////////////////////

    @Override
    public boolean removeValue(int value, ICause cause) throws ContradictionException {
        int inf = getLB();
        int sup = getUB();
        if (value == inf && value == sup) {
            solver.explainer.removeValue(this, value, cause);
            this.contradiction(cause, AbstractVariable.MSG_REMOVE);
        } else if (inf == value || value == sup) {
            EventType e;
            if (value == inf) {
                // todo: delta...
                LB.set(value + 1);
                e = EventType.INCLOW;
                cause = (cause != Cause.Null && cause.reactOnPromotion() ? Cause.Null : cause);
                filterOnGeq(cause, value + 1);
            } else {
                // todo: delta...
                UB.set(value - 1);
                e = EventType.DECUPP;
                cause = (cause != Cause.Null && cause.reactOnPromotion() ? Cause.Null : cause);
                filterOnLeq(cause, value - 1);
            }
            if (SIZE.get() > 0) {
                if (this.instantiated()) {
                    e = EventType.INSTANTIATE;
                    cause = (cause != Cause.Null && cause.reactOnPromotion() ? Cause.Null : cause);
                }
                this.notifyPropagators(e, cause);
            } else if (SIZE.get() == 0) {
                solver.explainer.removeValue(this, value, cause);
                this.contradiction(cause, MSG_EMPTY);
            }
            solver.explainer.removeValue(this, value, cause);
            return true;
        }
        return false;
    }

    @Override
    public boolean removeInterval(int from, int to, ICause cause) throws ContradictionException {
        if (from <= getLB()) {
            return updateLowerBound(to + 1, cause);
        } else if (getUB() <= to) {
            return updateUpperBound(from - 1, cause);
        }
        return false;
    }

    @Override
    public boolean instantiateTo(int value, ICause cause) throws ContradictionException {
        if (this.instantiated()) {
            if (value != this.getValue()) {
                solver.explainer.instantiateTo(this, value, cause);
                this.contradiction(cause, MSG_INST);
            }
            return false;
        } else if (contains(value)) {
            //todo: delta
            this.LB.set(value);
            this.UB.set(value);
            this.SIZE.set(1);

            filterOnLeq(cause, value);
            filterOnGeq(cause, value);

            this.notifyPropagators(EventType.INSTANTIATE, cause);
            solver.explainer.instantiateTo(this, value, cause);
            return true;
        } else {
            solver.explainer.instantiateTo(this, value, cause);
            this.contradiction(cause, MSG_UNKNOWN);
            return false;
        }
    }

    @Override
    public boolean updateLowerBound(int aValue, ICause cause) throws ContradictionException {
        int old = this.getLB();
        if (old < aValue) {
            if (this.getUB() < aValue) {
                solver.explainer.updateLowerBound(this, old, aValue, cause);
                this.contradiction(cause, MSG_LOW);
            } else {
                EventType e = EventType.INCLOW;
                //todo delta
                SIZE.add(old - aValue);
                LB.set(aValue);

                filterOnGeq(cause, aValue);

                if (instantiated()) {
                    e = EventType.INSTANTIATE;
                    cause = (cause != Cause.Null && cause.reactOnPromotion() ? Cause.Null : cause);
                }
                this.notifyPropagators(e, cause);

                solver.explainer.updateLowerBound(this, old, aValue, cause);
                return true;

            }
        }
        return false;
    }

    @Override
    public boolean updateUpperBound(int aValue, ICause cause) throws ContradictionException {
        int old = this.getUB();
        if (old > aValue) {
            if (this.getLB() > aValue) {
                solver.explainer.updateUpperBound(this, old, aValue, cause);
                this.contradiction(cause, MSG_UPP);
            } else {
                EventType e = EventType.DECUPP;
                //todo delta
                SIZE.add(aValue - old);
                UB.set(aValue);

                filterOnLeq(cause, aValue);

                if (instantiated()) {
                    e = EventType.INSTANTIATE;
                    cause = (cause != Cause.Null && cause.reactOnPromotion() ? Cause.Null : cause);
                }
                this.notifyPropagators(e, cause);
                solver.explainer.updateUpperBound(this, old, aValue, cause);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean contains(int aValue) {
        // based on "Bounds Consistency Techniques for Long Linear Constraint"
        return LB.get() <= aValue && aValue <= UB.get();
    }

    @Override
    public int nextValue(int aValue) {
        int lb = getLB();
        if (aValue < lb) {
            return lb;
        } else if (aValue < getUB()) {
            return aValue + 1;
        } else {
            return Integer.MAX_VALUE;
        }
    }

    @Override
    public int previousValue(int aValue) {
        int ub = getUB();
        if (aValue > ub) {
            return ub;
        } else if (aValue > getLB()) {
            return aValue - 1;
        } else {
            return Integer.MIN_VALUE;
        }
    }

    @Override
    public boolean hasEnumeratedDomain() {
        return false;
    }

    @Override
    public String toString() {
        if (instantiated()) {
            return String.format("(%s + %s) = %d", A.getName(), B.getName(), getValue());
        } else {
            StringBuilder s = new StringBuilder(20);
            s.append('{').append(getLB());
            int nb = 5;
            for (int i = nextValue(getLB()); i < Integer.MAX_VALUE && nb > 0; i = nextValue(i)) {
                s.append(',').append(i);
                nb--;
            }
            if (nb == 0) {
                s.append("...,").append(this.getUB());
            }
            s.append('}');

            return String.format("(%s + %s) = %s", A.getName(), B.getName(), s.toString());
//            return String.format("(%s + %s) = [%d, %d]", A, B, getLB(), getUB());
        }
    }

    /////////////// SERVICES REQUIRED FROM SUM //////////////////////////

    @Override
    public void backPropagate(int mask) throws ContradictionException {
        // one of the variable as changed externally, this involves a complete update of this
        if (!EventType.isRemove(mask)) {
            int elb = A.getLB() + B.getLB();
            int eub = A.getUB() + B.getUB();
            int ilb = LB.get();
            int iub = UB.get();
            EventType e = EventType.VOID;
            if (elb > ilb) {
                LB.set(elb);
                ilb = elb;
                e = EventType.INCLOW;
            }
            if (eub < iub) {
                UB.set(eub);
                iub = eub;
                if (e != EventType.VOID) {
                    e = EventType.BOUND;
                } else {
                    e = EventType.DECUPP;
                }
            }
            if (ilb > iub) {
                this.contradiction(null, MSG_EMPTY);
            }
            if (e == EventType.INCLOW) {
                filterOnLeq(null, iub);
            }
            if (e == EventType.DECUPP) {
                filterOnGeq(null, ilb);
            }
            if (ilb == iub) {
                notifyPropagators(EventType.INSTANTIATE, Cause.Null);
            } else {
                notifyPropagators(e, Cause.Null);
            }
        }
    }
}

