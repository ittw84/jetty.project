//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.message;

import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;

public class InputStreamMessageSink implements MessageSink
{
    private static final Logger LOG = Log.getLogger(ReaderMessageSink.class);
    private final Function<InputStream, Void> onStreamFunction;
    private final Executor executor;
    private MessageInputStream stream;
    private CountDownLatch dispatchCompleted = new CountDownLatch(1);

    public InputStreamMessageSink(Executor executor, Function<InputStream, Void> function)
    {
        this.executor = executor;
        this.onStreamFunction = function;
    }

    @Override
    public void accept(Frame frame, FrameCallback callback)
    {
        try
        {
            boolean first = false;

            if (stream == null)
            {
                stream = new MessageInputStream();
                first = true;
            }

            stream.accept(frame, callback);
            if (first)
            {
                dispatchCompleted = new CountDownLatch(1);
                executor.execute(() -> {
                    final MessageInputStream dispatchedStream = stream;
                    try
                    {
                        onStreamFunction.apply(dispatchedStream);
                    }
                    catch (Throwable t)
                    {
                        // processing of errors is the responsibility
                        // of the stream function
                        if (LOG.isDebugEnabled())
                        {
                            LOG.debug("Unhandled throwable", t);
                        }
                    }
                    // Returned from dispatch, stream should be closed
                    IO.close(dispatchedStream);
                    dispatchCompleted.countDown();
                });
            }
        }
        finally
        {
            //noinspection Duplicates
            if (frame.isFin())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("dispatch complete await() - {}", stream);
                try
                {
                    dispatchCompleted.await();
                }
                catch (InterruptedException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug(e);
                }
                stream = null;
            }
        }
    }
}