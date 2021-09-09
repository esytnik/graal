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
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.redefinition.ClassRedefinition;
import com.oracle.truffle.espresso.runtime.StaticObject;

/**
 * INVOKESPECIAL bytecode.
 *
 * <p>
 * The receiver must be included as the first element of the arguments passed to
 * {@link #execute(Method, StaticObject, Object[])}. e.g.
 * <code>invokeVirtual.execute(virtualMethod, args[0], args);</code>
 * </p>
 *
 * <p>
 * Method resolution does not perform any access checks, the caller is responsible to pass a
 * compatible receiver.
 * </p>
 */
@GenerateUncached
@NodeInfo(shortName = "INVOKESPECIAL")
public abstract class InvokeSpecial extends Node {

    public abstract Object execute(Method method, StaticObject receiver, Object[] args);

    @Specialization
    Object executeWithNullCheck(Method method, StaticObject receiver, Object[] args,
                    @Cached NullCheck nullCheck,
                    @Cached WithoutNullCheck invokeSpecial) {
        assert args[0] == receiver;
        return invokeSpecial.execute(method, nullCheck.execute(receiver), args);
    }

    @GenerateUncached
    @ReportPolymorphism
    @NodeInfo(shortName = "INVOKESPECIAL !nullcheck")
    public abstract static class WithoutNullCheck extends Node {

        protected static final int LIMIT = 4;

        public abstract Object execute(Method method, StaticObject receiver, Object[] args);

        @SuppressWarnings("unused")
        @Specialization(limit = "LIMIT", //
                        guards = {
                                        "method == cachedMethod",
                        }, //
                        assumptions = "resolvedMethod.getAssumption()")
        public Object callDirect(Method method, StaticObject receiver, Object[] args,
                        @Cached("method") Method cachedMethod,
                        // TODO(peterssen): Use the method's declaring class instead of the first
                        // receiver's class?
                        @Cached("methodLookup(cachedMethod, receiver)") Method.MethodVersion resolvedMethod,
                        @Cached("create(resolvedMethod.getCallTargetNoInit())") DirectCallNode directCallNode) {
            assert args[0] == receiver;
            assert !StaticObject.isNull(receiver);
            assert InvokeStatic.isInitializedOrInitializing(resolvedMethod.getMethod().getDeclaringKlass());
            return directCallNode.call(args);
        }

        @ReportPolymorphism.Megamorphic
        @Specialization(replaces = "callDirect")
        Object callIndirect(Method method, StaticObject receiver, Object[] args,
                        @Cached IndirectCallNode indirectCallNode) {
            assert !StaticObject.isNull(receiver);
            Method.MethodVersion resolvedMethod = methodLookup(method, receiver);
            assert InvokeStatic.isInitializedOrInitializing(resolvedMethod.getMethod().getDeclaringKlass());
            return indirectCallNode.call(resolvedMethod.getCallTarget(), receiver, args);
        }

        protected static Method.MethodVersion methodLookup(Method method, StaticObject receiver) {
            if (method.isRemovedByRedefition()) {
                /*
                 * Accept a slow path once the method has been removed put method behind a boundary
                 * to avoid a deopt loop.
                 */
                return ClassRedefinition.handleRemovedMethod(method, receiver.getKlass(), receiver).getMethodVersion();
            }
            return method.getMethodVersion();
        }
    }
}
