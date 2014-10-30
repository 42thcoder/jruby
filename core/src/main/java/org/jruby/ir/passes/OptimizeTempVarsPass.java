package org.jruby.ir.passes;

import org.jruby.ir.IRClosure;
import org.jruby.ir.IRScope;
import org.jruby.ir.instructions.*;
import org.jruby.ir.operands.Operand;
import org.jruby.ir.operands.TemporaryLocalVariable;
import org.jruby.ir.operands.TemporaryVariable;
import org.jruby.ir.operands.Variable;

import java.util.*;

public class OptimizeTempVarsPass extends CompilerPass {
    @Override
    public String getLabel() {
        return "Temporary Variable Reduction";
    }

    @Override
    public Object execute(IRScope s, Object... data) {
        for (IRClosure c: s.getClosures()) {
            run(c, false, true);
        }

        optimizeTmpVars(s);

        return null;
    }

    @Override
    public boolean invalidate(IRScope s) {
        // This runs after IR is built and before CFG is built.
        // Not reversible in the form it is written right now.
        return false;
    }

    private static TemporaryLocalVariable allocVar(Operand oldVar, IRScope s, List<TemporaryLocalVariable> freeVarsList, Map<Operand, Operand> newVarMap, boolean isResult, Instr producer) {
        // If we dont have a var mapping, get a new var -- try the free list first
        // and if none available, allocate a fresh one
        TemporaryLocalVariable newVar = (TemporaryLocalVariable)newVarMap.get(oldVar);
        if (newVar == null) {
            if (freeVarsList.isEmpty()) {
                newVar = s.createTemporaryVariable();
            } else {
                newVar = freeVarsList.remove(0);
                if (isResult) {
                    newVar = (TemporaryLocalVariable)newVar.clone(null);
                }
            }
            newVarMap.put(oldVar, newVar);
        } else if (isResult) {
            newVar = (TemporaryLocalVariable)newVar.clone(null);
            newVarMap.put(oldVar, newVar);
        }

        if (producer != null) {
            newVar.producer = producer;
        }

        return newVar;
    }

    private static void freeVar(TemporaryLocalVariable newVar, List<TemporaryLocalVariable> freeVarsList) {
        // Put the new var onto the free list (but only if it is not already there).
        if (!freeVarsList.contains(newVar)) freeVarsList.add(0, newVar);
    }

    private static void optimizeTmpVars(IRScope s) {
        // Cannot run after CFG has been built in the form it has been written here.
        if (s.getCFG() != null) return;

        // Pass 1: Analyze instructions and find use and def count of temporary variables
        Map<TemporaryVariable, List<Instr>> tmpVarUses = new HashMap<TemporaryVariable, List<Instr>>();
        Map<TemporaryVariable, List<Instr>> tmpVarDefs = new HashMap<TemporaryVariable, List<Instr>>();
        for (Instr i: s.getInstrs()) {
            for (Variable v: i.getUsedVariables()) {
                 if (v instanceof TemporaryVariable) {
                     TemporaryVariable tv = (TemporaryVariable)v;
                     List<Instr> uses = tmpVarUses.get(tv);
                     if (uses == null) {
                         uses = new ArrayList<Instr>();
                         tmpVarUses.put(tv, uses);
                     }
                     uses.add(i);
                 }
            }
            if (i instanceof ResultInstr) {
                Variable v = ((ResultInstr)i).getResult();
                if (v instanceof TemporaryVariable) {
                     TemporaryVariable tv = (TemporaryVariable)v;
                     List<Instr> defs = tmpVarDefs.get(tv);
                     if (defs == null) {
                         defs = new ArrayList<Instr>();
                         tmpVarDefs.put(tv, defs);
                     }
                     defs.add(i);
                }
            }
        }

        // Pass 2: Transform code and do additional analysis:
        // * If the result of this instr. has not been used, mark it dead
        // * Find copies where constant values are set
        Map<TemporaryVariable, Variable> removableCopies = new HashMap<TemporaryVariable, Variable>();
        ListIterator<Instr> instrs = s.getInstrs().listIterator();
        while (instrs.hasNext()) {
            Instr i = instrs.next();

            if (i instanceof ResultInstr) {
                Variable v = ((ResultInstr)i).getResult();
                if (v instanceof TemporaryVariable) {
                    // Deal with this code pattern:
                    //    %v = ...
                    // %v not used anywhere
                    List<Instr> uses = tmpVarUses.get((TemporaryVariable)v);
                    List<Instr> defs = tmpVarDefs.get((TemporaryVariable)v);
                    if (uses == null) {
                        if (i instanceof CopyInstr) {
                            i.markDead();
                            instrs.remove();
                        } else if (i instanceof CallInstr) {
                            instrs.set(((CallInstr)i).discardResult());
                        } else {
                            i.markUnusedResult();
                        }
                    }
                    // Deal with this code pattern:
                    //    %v = <some-operand>
                    //    .... %v ...
                    // %v not used or defined anywhere else
                    // So, %v can be replaced by the operand
                    else if ((uses.size() == 1) && (defs != null) && (defs.size() == 1) && (i instanceof CopyInstr)) {
                        Instr soleUse = uses.get(0);
                        // Conservatively avoid optimizing return values since
                        // intervening cloned ensure block code can modify the
                        // copy source (if it is a variable).
                        //
                        // Ex:
                        //    v = 1
                        //    %v_1 = v
                        // L1:
                        //    v = 2
                        //    return %v_1 <-- cannot be replaced with v
                        //    ....
                        if (!(soleUse instanceof ReturnInstr)) {
                            CopyInstr ci = (CopyInstr)i;
                            Operand src = ci.getSource();
                            i.markDead();
                            instrs.remove();

                            // Clear uses/defs for old-var
                            tmpVarUses.remove(v);
                            tmpVarDefs.remove(v);

                            // Fix up use
                            Map<Operand, Operand> copyMap = new HashMap<Operand, Operand>();
                            copyMap.put(v, src);
                            soleUse.simplifyOperands(copyMap, true);
                            if (src instanceof TemporaryLocalVariable) {
                                List<Instr> srcUses = tmpVarUses.get(src);
                                srcUses.add(soleUse);
                            }
                        }
                    }
                }
                // Deal with this code pattern:
                //    1: %v = ... (not a copy)
                //    2: x = %v
                // If %v is not used anywhere else, the result of 1. can be updated to use x and 2. can be removed
                //
                // NOTE: consider this pattern:
                //    %v = <operand> (copy instr)
                //    x = %v
                // This code will have been captured in the previous if branch which would have deleted %v = 5
                // Hence the check for whether the src def instr is dead
                else if (i instanceof CopyInstr) {
                    CopyInstr ci = (CopyInstr)i;
                    Operand src = ci.getSource();
                    if (src instanceof TemporaryVariable) {
                        TemporaryVariable vsrc = (TemporaryVariable)src;
                        List<Instr> uses = tmpVarUses.get(vsrc);
                        List<Instr> defs = tmpVarDefs.get(vsrc);
                        if ((uses.size() == 1) && (defs.size() == 1)) {
                            Instr soleDef = defs.get(0);
                            if (!soleDef.isDead()) {
                                Variable ciRes = ci.getResult();
                                // Fix up def
                                ((ResultInstr)soleDef).updateResult(ciRes);
                                ci.markDead();
                                instrs.remove();

                                // Update defs for ciRes if it is a tmp-var
                                if (ciRes instanceof TemporaryVariable) {
                                    List<Instr> ciDefs = tmpVarDefs.get(ciRes);
                                    ciDefs.remove(ci);
                                    ciDefs.add(soleDef);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pass 3: Compute last use of temporary variables -- this effectively is the
        // end of the live range that started with its first definition. This implicitly
        // encodes the live range of the temporary variable.
        //
        // These live ranges are valid because these instructions are generated from an AST
        // and they haven't been rearranged yet.  In addition, since temporaries are used to
        // communicate results from lower levels to higher levels in the tree, a temporary
        // defined outside a loop cannot be used within the loop.  So, the first definition
        // of a temporary and the last use of the temporary delimit its live range.
        //
        // Caveat
        // ------
        // %current-scope and %current-module are the two "temporary" variables that violate
        // this contract right now since they are used everywhere in the scope.
        // So, in the presence of loops, we:
        // - either assume that the live range of these  variables extends to
        //   the end of the outermost loop in which they are used
        // - or we do not rename %current-scope and %current-module in such scopes.
        //
        // SSS FIXME: For now, we just extend the live range of these vars all the
        // way to the end of the scope!
        //
        // NOTE: It is sufficient to just track last use for renaming purposes.
        // At the first definition, we allocate a variable which then starts the live range
        Map<TemporaryVariable, Integer> lastVarUseOrDef = new HashMap<TemporaryVariable, Integer>();
        int iCount = -1;
        for (Instr i: s.getInstrs()) {
            iCount++;

            // update last use/def
            if (i instanceof ResultInstr) {
                Variable v = ((ResultInstr)i).getResult();
                if (v instanceof TemporaryVariable) lastVarUseOrDef.put((TemporaryVariable)v, iCount);
            }

            // update last use/def
            for (Variable v: i.getUsedVariables()) {
                if (v instanceof TemporaryVariable) lastVarUseOrDef.put((TemporaryVariable)v, iCount);
            }
        }

        // If the scope has loops, extend live range of %current-module and %current-scope
        // to end of scope (see note earlier).
        if (s.hasLoops()) {
            lastVarUseOrDef.put((TemporaryVariable)s.getCurrentScopeVariable(), iCount);
            lastVarUseOrDef.put((TemporaryVariable)s.getCurrentModuleVariable(), iCount);
        }

        // Pass 4: Reallocate temporaries based on last uses to minimize # of unique vars.
        // Replace all single use operands with constants they were assigned to.
        // Using operand -> operand signature because simplifyOperands works on operands
        Map<Operand, Operand>   newVarMap    = new HashMap<Operand, Operand>();
        List<TemporaryLocalVariable> freeVarsList = new ArrayList<TemporaryLocalVariable>();
        iCount = -1;
        s.resetTemporaryVariables();

        for (Instr i: s.getInstrs()) {
            iCount++;

            // Assign new vars
            Variable result = null;
            if (i instanceof ResultInstr) {
                result = ((ResultInstr)i).getResult();
                if (result instanceof TemporaryVariable) {
                    List<Instr> uses = tmpVarUses.get((TemporaryVariable)result);
                    List<Instr> defs = tmpVarDefs.get((TemporaryVariable)result);
                    allocVar(result, s, freeVarsList, newVarMap, true, (uses != null && (uses.size() == 1) && defs != null && (defs.size() == 1)) ? i : null);
                }
            }
            for (Variable v: i.getUsedVariables()) {
                if (v instanceof TemporaryVariable) allocVar(v, s, freeVarsList, newVarMap, false, null);
            }

            // Free dead vars
            if ((result instanceof TemporaryVariable) && lastVarUseOrDef.get((TemporaryVariable)result) == iCount) {
                freeVar((TemporaryLocalVariable)newVarMap.get(result), freeVarsList);
            }
            for (Variable v: i.getUsedVariables()) {
                if (v instanceof TemporaryVariable) {
                    TemporaryVariable tv = (TemporaryVariable)v;
                    if (lastVarUseOrDef.get(tv) == iCount) freeVar((TemporaryLocalVariable)newVarMap.get(tv), freeVarsList);
                }
            }

            // Rename
            i.renameVars(newVarMap);
        }
    }
}
