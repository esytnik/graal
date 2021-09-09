/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.nodes.bytecodes;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.redefinition.ClassRedefinition;
import com.oracle.truffle.espresso.runtime.StaticObject;

/**
 * INVOKEVIRTUAL bytecode.
 *
 * <p>
 * The receiver must be included as the first element of the arguments passed to
 * {@link #execute(StaticObject, Object[])}. e.g.
 * <code>invokeVirtual.execute(virtualMethod, args[0], args);</code>
 * </p>
 *
 * <p>
 * (Virtual) method resolution does not perform any access checks, the caller is responsible to pass
 * a compatible receiver.
 * </p>
 *
 * <h3>Exceptions</h3>
 * <ul>
 * <li>Throws guest {@link AbstractMethodError} if the resolved method is abstract.</li>
 * </ul>
 */
@NodeInfo(shortName = "INVOKEVIRTUAL")
public abstract class InvokeVirtual extends Node {

    final Method resolutionSeed;

    protected InvokeVirtual(Method resolutionSeed) {
        this.resolutionSeed = resolutionSeed;
    }

    protected abstract Object execute(StaticObject receiver, Object[] args);

    @Specialization
    Object executeWithNullCheck(StaticObject receiver, Object[] args,
                    @Cached NullCheck nullCheck,
                    @Cached("create(resolutionSeed)") WithoutNullCheck invokeVirtual) {
        assert args[0] == receiver;
        return invokeVirtual.execute(nullCheck.execute(receiver), args);
    }

    @ReportPolymorphism
    @ImportStatic(InvokeVirtual.class)
    @NodeInfo(shortName = "INVOKEVIRTUAL !nullcheck")
    public abstract static class WithoutNullCheck extends Node {

        final Method resolutionSeed;

        protected static final int LIMIT = 8;

        WithoutNullCheck(Method resolutionSeed) {
            this.resolutionSeed = resolutionSeed;
        }

        public abstract Object execute(StaticObject receiver, Object[] args);

        @Specialization(guards = "!resolutionSeed.isAbstract()", //
                        assumptions = { //
                                        "resolutionSeed.getLeafAssumption()",
                                        "resolvedMethod.getAssumption()"
                        })
        Object callLeaf(StaticObject receiver, Object[] args,
                        @Cached("methodLookup(resolutionSeed, receiver)") Method.MethodVersion resolvedMethod,
                        @Cached("create(resolvedMethod.getCallTargetNoInit())") DirectCallNode directCallNode) {
            assert args[0] == receiver;
            assert !StaticObject.isNull(receiver);
            assert resolvedMethod.getMethod() == resolutionSeed;
            assert InvokeStatic.isInitializedOrInitializing(resolvedMethod.getMethod().getDeclaringKlass());
            return directCallNode.call(args);
        }

        @SuppressWarnings("unused")
        @Specialization(limit = "LIMIT", //
                        replaces = "callLeaf", //
                        guards = "receiver.getKlass() == cachedKlass", //
                        assumptions = "resolvedMethod.getAssumption()")
        Object callDirect(StaticObject receiver, Object[] args,
                        @Cached("receiver.getKlass()") Klass cachedKlass,
                        @Cached("methodLookup(resolutionSeed, receiver)") Method.MethodVersion resolvedMethod,
                        @Cached("create(resolvedMethod.getCallTargetNoInit())") DirectCallNode directCallNode) {
            assert args[0] == receiver;
            assert !StaticObject.isNull(receiver);
            assert InvokeStatic.isInitializedOrInitializing(resolvedMethod.getMethod().getDeclaringKlass());
            return directCallNode.call(args);
        }

        @Specialization
        @ReportPolymorphism.Megamorphic
        Object callIndirect(StaticObject receiver, Object[] args,
                        @Cached IndirectCallNode indirectCallNode) {
            assert args[0] == receiver;
            assert !StaticObject.isNull(receiver);
            // vtable lookup.
            Method.MethodVersion target = methodLookup(resolutionSeed, receiver);
            assert InvokeStatic.isInitializedOrInitializing(target.getMethod().getDeclaringKlass());
            return indirectCallNode.call(target.getCallTarget(), args);
        }
    }

    static Method.MethodVersion methodLookup(Method resolutionSeed, StaticObject receiver) {
        if (resolutionSeed.isRemovedByRedefition()) {
            /*
             * Accept a slow path once the method has been removed put method behind a boundary to
             * avoid a deopt loop.
             */
            return ClassRedefinition.handleRemovedMethod(resolutionSeed, receiver.getKlass(), receiver).getMethodVersion();
        }
        /*
         * Surprisingly, INVOKEVIRTUAL can try to invoke interface methods, even non-default ones.
         * Good thing is, miranda methods are taken care of at vtable creation !
         */
        Klass receiverKlass = receiver.getKlass();
        int vtableIndex = resolutionSeed.getVTableIndex();
        Method.MethodVersion target = null;
        if (receiverKlass.isArray()) {
            target = receiverKlass.getSuperKlass().vtableLookup(vtableIndex).getMethodVersion();
        } else {
            target = receiverKlass.vtableLookup(vtableIndex).getMethodVersion();
        }
        if (!target.getMethod().hasCode()) {
            Meta meta = receiver.getKlass().getMeta();
            throw meta.throwException(meta.java_lang_AbstractMethodError);
        }
        return target;
    }

    @GenerateUncached
    @NodeInfo(shortName = "INVOKEVIRTUAL dynamic")
    public abstract static class Dynamic extends Node {

        protected static final int LIMIT = 4;

        public abstract Object execute(Method resolutionSeed, StaticObject receiver, Object[] args);

        @Specialization
        Object executeWithNullCheck(Method resolutionSeed, StaticObject receiver, Object[] args,
                        @Cached NullCheck nullCheck,
                        @Cached WithoutNullCheck invokeVirtual) {
            assert args[0] == receiver;
            return invokeVirtual.execute(resolutionSeed, nullCheck.execute(receiver), args);
        }

        @GenerateUncached
        @ReportPolymorphism
        @NodeInfo(shortName = "INVOKEVIRTUAL dynamic !nullcheck")
        public abstract static class WithoutNullCheck extends Node {

            protected static final int LIMIT = 4;

            public abstract Object execute(Method resolutionSeed, StaticObject receiver, Object[] args);

            @Specialization(limit = "LIMIT", //
                            guards = "resolutionSeed == cachedResolutionSeed")
            Object doCached(@SuppressWarnings("unused") Method resolutionSeed, StaticObject receiver, Object[] args,
                            @SuppressWarnings("unused") @Cached("resolutionSeed") Method cachedResolutionSeed,
                            @Cached("create(cachedResolutionSeed)") InvokeVirtual.WithoutNullCheck invokeVirtual) {
                assert args[0] == receiver;
                assert !StaticObject.isNull(receiver);
                return invokeVirtual.execute(receiver, args);
            }

            @ReportPolymorphism.Megamorphic
            @Specialization(replaces = "doCached")
            Object doGeneric(Method resolutionSeed, StaticObject receiver, Object[] args,
                            @Cached IndirectCallNode indirectCallNode) {
                assert args[0] == receiver;
                assert !StaticObject.isNull(receiver);
                Method.MethodVersion target = methodLookup(resolutionSeed, receiver);
                assert target.getMethod().getDeclaringKlass().isInitialized();
                return indirectCallNode.call(target.getCallTarget(), args);
            }
        }
    }
}
