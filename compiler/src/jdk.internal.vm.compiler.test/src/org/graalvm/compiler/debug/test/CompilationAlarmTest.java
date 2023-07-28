/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package org.graalvm.compiler.debug.test;

import java.util.Optional;

import org.graalvm.compiler.api.directives.GraalDirectives;
import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.core.GraalCompiler;
import org.graalvm.compiler.core.GraalCompiler.Request;
import org.graalvm.compiler.core.common.CompilationIdentifier;
import org.graalvm.compiler.core.common.util.CompilationAlarm;
import org.graalvm.compiler.core.test.GraalCompilerTest;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.debug.TTY;
import org.graalvm.compiler.lir.asm.CompilationResultBuilderFactory;
import org.graalvm.compiler.nodes.GraphState;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.StructuredGraph.AllowAssumptions;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.phases.tiers.LowTierContext;
import org.graalvm.compiler.phases.tiers.Suites;
import org.junit.Assert;
import org.junit.Test;

import jdk.vm.ci.meta.ResolvedJavaMethod;

public class CompilationAlarmTest extends GraalCompilerTest {

    public static final boolean LOG = false;

    private Suites getSuites(int waitSeconds, OptionValues opt) {
        if (waitSeconds == 0) {
            return createSuites(opt);
        } else {
            Suites s = createSuites(opt);
            s = s.copy();
            s.getLowTier().appendPhase(new BasePhase<LowTierContext>() {

                @Override
                public Optional<NotApplicable> notApplicableTo(GraphState graphState) {
                    return ALWAYS_APPLICABLE;
                }

                @Override
                protected void run(StructuredGraph graph, LowTierContext context) {
                    int msWaited = 0;
                    int callsToCheckProgress = 0;
                    if (LOG) {
                        TTY.printf("Starting to wait %s seconds - graph event counter %s %n", waitSeconds * 1000, graph.getEventCounter());
                    }
                    while (true) {
                        if (msWaited >= waitSeconds * 1000) {
                            if (LOG) {
                                TTY.printf("Finished waiting %s seconds - graph event counter %s, calls to check progress %s %n", waitSeconds * 1000, graph.getEventCounter(), callsToCheckProgress);
                            }
                            return;
                        }
                        try {
                            Thread.sleep(1);
                            CompilationAlarm.checkProgress(graph);
                            callsToCheckProgress++;
                            msWaited += 1;
                        } catch (InterruptedException e) {
                            GraalError.shouldNotReachHere(e);
                        }
                    }
                }
            });
            return s;
        }
    }

    private Thread getCompilationThreadWithWait(int waitSeconds, String snippet, OptionValues opt, String exceptedExceptionTest) {
        Thread t = new Thread(new Runnable() {

            @Override
            public void run() {
                StructuredGraph graph = parseEager(getResolvedJavaMethod(snippet), AllowAssumptions.YES, opt);
                ResolvedJavaMethod codeOwner = graph.method();
                CompilationIdentifier compilationId = getOrCreateCompilationId(codeOwner, graph);
                Request<CompilationResult> request = new Request<>(graph, codeOwner, getProviders(), getBackend(), getDefaultGraphBuilderSuite(), getOptimisticOptimizations(),
                                graph.getProfilingInfo(), getSuites(waitSeconds, opt), createLIRSuites(opt), new CompilationResult(compilationId), CompilationResultBuilderFactory.Default, null, true);

                try {
                    GraalCompiler.compile(request);
                    if (exceptedExceptionTest != null) {
                        Assert.fail("Must throw exception");
                    }
                } catch (Throwable t1) {
                    if (exceptedExceptionTest == null) {
                        Assert.fail("Must except exception but found no excepted exception but " + t1.getMessage());
                    }
                    if (!t1.getMessage().contains(exceptedExceptionTest)) {
                        Assert.fail("Excepted exception to contain text:" + exceptedExceptionTest + " but exception did not contain text " + t1.getMessage());
                        throw t1;
                    }
                }
            }
        });
        return t;
    }

    public static void snippet() {
        GraalDirectives.sideEffect();
    }

    private static OptionValues getOptionsWithTimeOut(int seconds) {
        OptionValues opt = new OptionValues(getInitialOptions(), CompilationAlarm.Options.CompilationNoProgressPeriod, (double) seconds);
        return opt;
    }

    @Test
    public void testSingleThreadNoTimeout() throws InterruptedException {
        Thread t1 = getCompilationThreadWithWait(1, "snippet", getOptionsWithTimeOut(3), null);
        t1.start();
        t1.join();
    }

    @Test
    public void testSingleThreadTimeOut() throws InterruptedException {
        Thread t1 = getCompilationThreadWithWait(10, "snippet", getOptionsWithTimeOut(3), "Observed identical stack traces for 3 seconds");
        t1.start();
        t1.join();
    }

    @Test
    public void testMultiThreadNoTimeout() throws InterruptedException {
        Thread t1 = getCompilationThreadWithWait(1, "snippet", getOptionsWithTimeOut(3), null);
        Thread t2 = getCompilationThreadWithWait(1, "snippet", getOptionsWithTimeOut(3), null);
        t1.start();
        t2.start();
        t1.join();
        t2.join();
    }

    @Test
    public void testMultiThreadOneTimeout() throws InterruptedException {
        Thread t1 = getCompilationThreadWithWait(1, "snippet", getOptionsWithTimeOut(3), null);
        t1.start();
        t1.join();

        Thread t2 = getCompilationThreadWithWait(10, "snippet", getOptionsWithTimeOut(3), "Observed identical stack traces for 3 seconds");
        t2.start();
        t2.join();
    }

    @Test
    public void testMultiThreadMultiTimeout() throws InterruptedException {
        Thread t1 = getCompilationThreadWithWait(10, "snippet", getOptionsWithTimeOut(9), "Observed identical stack traces for 9 seconds");
        t1.start();
        t1.join();

        Thread t2 = getCompilationThreadWithWait(10, "snippet", getOptionsWithTimeOut(5), "Observed identical stack traces for 5 seconds");
        t2.start();
        t2.join();
    }

}
