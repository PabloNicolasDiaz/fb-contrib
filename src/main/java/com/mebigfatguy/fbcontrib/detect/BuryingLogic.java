/*
 * fb-contrib - Auxiliary detectors for Java programs
 * Copyright (C) 2005-2017 Dave Brosius
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.mebigfatguy.fbcontrib.detect;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.Iterator;

import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.CodeException;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.Type;

import com.mebigfatguy.fbcontrib.utils.BugType;
import com.mebigfatguy.fbcontrib.utils.CollectionUtils;
import com.mebigfatguy.fbcontrib.utils.OpcodeUtils;
import com.mebigfatguy.fbcontrib.utils.StopOpcodeParsingException;
import com.mebigfatguy.fbcontrib.utils.ToString;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.ba.ClassContext;

/**
 * looks for relatively large if blocks of code, where you unconditionally return from them, and then follow that with an unconditional return of a small block.
 * This places the bulk of the logic to the right indentation-wise, making it more difficult to read than needed. It would be better to invert the logic of the
 * if block, and immediately return, allowing the bulk of the logic to be move to the left, for easier reading.
 */
public class BuryingLogic extends BytecodeScanningDetector {

    private static final String BURY_LOGIC_LOW_RATIO_PROPERTY = "fb-contrib.bl.low_ratio";
    private static final String BURY_LOGIC_NORMAL_RATIO_PROPERTY = "fb-contrib.bl.normal_ratio";
    private static final double LOW_BUG_RATIO_LIMIT = 16.0;
    private static final double NORMAL_BUG_RATIO_LIMIT = 30.0;

    private final static BitSet resetOps = new BitSet();
    static {
        resetOps.set(PUTFIELD);
        resetOps.set(PUTSTATIC);
        resetOps.set(POP);
        resetOps.set(POP2);
        resetOps.set(TABLESWITCH);
        resetOps.set(LOOKUPSWITCH);
        resetOps.set(MONITORENTER);
        resetOps.set(MONITOREXIT);
    }

    private BugReporter bugReporter;
    private OpcodeStack stack;
    private IfBlocks ifBlocks;
    /**
     * if an previous if block has been closed off with a return, hold onto it.
     */
    private IfBlock activeUnconditional;
    private BitSet casePositions;
    private double lowBugRatioLimit;
    private double normalBugRatioLimit;
    private BitSet catchPCs;
    private BitSet gotoBranchPCs;
    /**
     * if we've processed an if block, we want to avoid else ifs, so don't start looking for a new if branch, until some instruction that can't be part of a
     * conditional is found
     */
    private boolean lookingForResetOp;

    public BuryingLogic(BugReporter bugReporter) {
        this.bugReporter = bugReporter;

        String lowRatio = System.getProperty(BURY_LOGIC_LOW_RATIO_PROPERTY);
        try {
            if (lowRatio == null) {
                lowBugRatioLimit = LOW_BUG_RATIO_LIMIT;
            } else {
                lowBugRatioLimit = Double.parseDouble(lowRatio);
                if (lowBugRatioLimit <= 0) {
                    lowBugRatioLimit = LOW_BUG_RATIO_LIMIT;
                }
            }
        } catch (NumberFormatException e) {
            lowBugRatioLimit = LOW_BUG_RATIO_LIMIT;
        }

        String normalRatio = System.getProperty(BURY_LOGIC_NORMAL_RATIO_PROPERTY);
        try {
            if (normalRatio == null) {
                normalBugRatioLimit = NORMAL_BUG_RATIO_LIMIT;
            } else {
                normalBugRatioLimit = Double.parseDouble(normalRatio);
                if (normalBugRatioLimit <= 0) {
                    normalBugRatioLimit = NORMAL_BUG_RATIO_LIMIT;
                }
            }
        } catch (NumberFormatException e) {
            normalBugRatioLimit = NORMAL_BUG_RATIO_LIMIT;
        }
    }

    @Override
    public void visitClassContext(ClassContext classContext) {
        try {
            stack = new OpcodeStack();
            ifBlocks = new IfBlocks();
            gotoBranchPCs = new BitSet();
            casePositions = new BitSet();
            super.visitClassContext(classContext);
        } finally {
            stack = null;
            ifBlocks = null;
            catchPCs = null;
            gotoBranchPCs = null;
            casePositions = null;
        }
    }

    @Override
    public void visitCode(Code obj) {
        Method m = getMethod();
        if (m.getReturnType() == Type.VOID) {
            return;
        }

        stack.resetForMethodEntry(this);
        ifBlocks.clear();
        activeUnconditional = null;

        CodeException[] ces = obj.getExceptionTable();
        if (CollectionUtils.isEmpty(ces)) {
            catchPCs = null;
        } else {
            catchPCs = new BitSet();
            for (CodeException ce : ces) {
                catchPCs.set(ce.getHandlerPC());
            }
        }
        gotoBranchPCs.clear();
        casePositions.clear();
        lookingForResetOp = false;

        try {
            super.visitCode(obj);
        } catch (StopOpcodeParsingException e) {
            // reported an issue, so get out
        }
    }

    /**
     * the difficult problem is to figure out when you are at the bottom of an if/else chain when all the above if/else blocks leave via returns. then there is
     * only one branch target to the statement after the last else, which is indistinquishable from a simple if/else.
     */
    @Override
    public void sawOpcode(int seen) {

        try {
            int removed = ifBlocks.removeBlocksAtPC(getPC());

            if (removed > 1) {
                activeUnconditional = null;
            } else if (removed == 1) {
                lookingForResetOp = true;
            }

            if (!casePositions.isEmpty()) {
                int firstCasePos = casePositions.nextSetBit(0);
                if (firstCasePos == getPC()) {
                    casePositions.clear(firstCasePos);
                    activeUnconditional = null;
                    lookingForResetOp = false;
                }
            }

            if (lookingForResetOp) {
                if (isResetOp(seen)) {
                    lookingForResetOp = false;
                } else {
                    return;
                }
            }

            if (isBranch(seen)) {
                if (activeUnconditional != null) {
                    activeUnconditional = null;
                    if (!ifBlocks.isEmpty()) {
                        ifBlocks.removeLast(getPC());
                    }
                }

                int target = getBranchTarget();

                if (getBranchOffset() > 0) {
                    if ((seen == GOTO) || (seen == GOTO_W)) {
                        gotoBranchPCs.set(target);
                    } else if ((catchPCs == null) || !catchPCs.get(getNextPC())) {
                        ifBlocks.add(new IfBlock(getNextPC(), target));
                    }
                } else {
                    ifBlocks.removeLoopBlocks(target);
                }
            } else if (isReturn(seen)) {
                if ((activeUnconditional != null) && !gotoBranchPCs.get(activeUnconditional.getEnd())) {

                    int ifSize = activeUnconditional.getEnd() - activeUnconditional.getStart();
                    int elseSize = getPC() - activeUnconditional.getEnd();

                    double ratio = (double) ifSize / (double) elseSize;
                    if (ratio > lowBugRatioLimit) {
                        bugReporter
                                .reportBug(new BugInstance(this, BugType.BL_BURYING_LOGIC.name(), ratio > normalBugRatioLimit ? NORMAL_PRIORITY : LOW_PRIORITY)
                                        .addClass(this).addMethod(this).addSourceLineRange(this, activeUnconditional.getStart(), activeUnconditional.getEnd()));
                        throw new StopOpcodeParsingException();
                    }
                } else if (!ifBlocks.isEmpty() && (getNextPC() == ifBlocks.getFirst().getEnd()) && !gotoAcrossPC(getNextPC())) {
                    activeUnconditional = ifBlocks.getFirst();
                }
                lookingForResetOp = true;
            } else if ((seen == TABLESWITCH) || (seen == LOOKUPSWITCH)) {
                int[] offsets = getSwitchOffsets();
                int pc = getPC();
                for (int offset : offsets) {
                    casePositions.set(pc + offset);
                }
            }
        } finally {
            stack.sawOpcode(this, seen);
        }
    }

    /**
     * returns whether the last downward branching jump seen crosses over the current location
     *
     * @param pc
     *            the current location
     * @return if the last if statement branched over here
     */
    private boolean gotoAcrossPC(int pc) {

        int target = gotoBranchPCs.previousSetBit(Integer.MAX_VALUE);
        return (target > pc);
    }

    /**
     * determines if this opcode couldn't be part of a conditional expression or at least is very unlikely to be so.
     *
     * @param seen
     *            the currently parse opcode
     * @return if this operation resets the looking for conditionals
     */
    private boolean isResetOp(int seen) {
        return resetOps.get(seen) || OpcodeUtils.isStore(seen) || OpcodeUtils.isReturn(seen)
                || ((OpcodeUtils.isInvoke(seen) && getSigConstantOperand().endsWith(")V")) || (isBranch(seen) && (getBranchOffset() < 0)));
    }

    /**
     * represents all the if blocks in a method
     */
    static class IfBlocks {
        private Deque<IfBlock> blocks;

        public IfBlocks() {
            blocks = new ArrayDeque<>();
        }

        public void add(IfBlock block) {
            if (blocks.isEmpty()) {
                blocks.addLast(block);
                return;
            }

            IfBlock lastBlock = blocks.getLast();

            if (block.getStart() > lastBlock.getEnd()) {
                blocks.addLast(block);
            } else {
                lastBlock.getSubIfBlocks().add(block);
            }
        }

        public IfBlock getFirst() {
            if (blocks.isEmpty()) {
                return null;
            }

            return blocks.getFirst();
        }

        public void clear() {
            blocks.clear();
        }

        public boolean isEmpty() {
            return blocks.isEmpty();
        }

        public IfBlock removeLast(int pc) {
            if (blocks.isEmpty()) {
                return null;
            }

            IfBlock lastBlock = blocks.getLast();
            if (pc > lastBlock.getEnd()) {
                return blocks.removeLast();
            } else {
                if (lastBlock.hasSubBlocks()) {
                    return lastBlock.getSubIfBlocks().removeLast(pc);
                }

                return null;
            }
        }

        /**
         * remove all if blocks that are contained within a loop, once that loop has ended
         *
         * @param target
         *            the start of the loop block
         */
        public void removeLoopBlocks(int target) {
            Iterator<IfBlock> it = blocks.descendingIterator();
            while (it.hasNext()) {
                if (it.next().getStart() >= target) {
                    it.remove();
                } else {
                    return;
                }
            }
        }

        public int removeBlocksAtPC(int pc) {
            if (blocks.isEmpty()) {
                return 0;
            }

            int removed = 0;
            Iterator<IfBlock> it = blocks.iterator();
            while (it.hasNext()) {
                IfBlock block = it.next();
                if (pc >= block.getStart()) {
                    if (block.hasSubBlocks()) {
                        removed += block.getSubIfBlocks().removeBlocksAtPC(pc);
                    }
                }

                if (pc >= block.getEnd()) {
                    it.remove();
                    removed++;
                }
            }

            return removed;
        }

        @Override
        public String toString() {
            return ToString.build(this);
        }
    }

    /**
     * represents the byte offset code range of code that is executed inside an if block
     */
    static class IfBlock {
        private int start;
        private int end;
        private IfBlocks subBlocks;

        public IfBlock(int s, int e) {
            start = s;
            end = e;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public boolean hasSubBlocks() {
            return (subBlocks == null) || !subBlocks.isEmpty();
        }

        public IfBlocks getSubIfBlocks() {
            if (subBlocks == null) {
                subBlocks = new IfBlocks();
            }
            return subBlocks;
        }

        @Override
        public String toString() {
            return ToString.build(this);
        }
    }
}
