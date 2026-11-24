package com.maplesu.core.ui.webui

import com.maplesu.core.ui.webui.file.KsuIO
import com.maplesu.core.ui.webui.file.ManagedInputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WebUiIoLifecycleTest {
    private fun waitFor(message:String, predicate:()->Boolean) {
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while(!predicate()) { if(System.nanoTime()>deadline) fail(message);Thread.sleep(2) }
    }

    @Test fun aDisposedPageRejectsLateStreamOpenRequests() {
        val io = KsuIO()
        assertTrue(io.canOpenStream())
        io.destroy()
        assertFalse(io.canOpenStream())
    }

    @Test fun destructionClosesInputAndOutputAndCanBeRepeated() {
        val io = KsuIO()
        val closed = java.util.concurrent.atomic.AtomicInteger()
        io.openInputStreams["input"] = ManagedInputStream(object : ByteArrayInputStream(byteArrayOf(4)) {
            override fun close() { closed.incrementAndGet(); super.close() }
        })
        io.openOutputStreams["output"] = object : ByteArrayOutputStream() {
            override fun close() { closed.incrementAndGet(); super.close() }
        }
        io.destroy()
        io.destroy()
        waitFor("streams not closed"){closed.get()==2}
        assertEquals("", io.FileInputStream().read("input"))
        assertFalse(io.FileOutputStream().writeByte("output", 1))
        assertEquals(0, io.openInputStreams.size)
        assertEquals(0, io.openOutputStreams.size)
    }

    @Test fun mixedStreamBudgetAndParallelOpenRequestsRespectTheSinglePageLimit() {
        val io = KsuIO()
        val ready = java.util.concurrent.CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val calls = (0 until 80).map { index ->
            Thread {
                ready.await()
                runCatching {
                    if (index % 2 == 0) io.openInput { ByteArrayInputStream(byteArrayOf(1)) }
                    else io.openOutput { ByteArrayOutputStream() }
                }.onSuccess { accepted.incrementAndGet() }
            }.apply { start() }
        }
        ready.countDown(); calls.forEach { it.join() }
        assertEquals(50, accepted.get())
        assertEquals(50, io.openInputStreams.size + io.openOutputStreams.size)
        io.destroy()
        waitFor("stream maps not reclaimed"){io.openInputStreams.isEmpty() && io.openOutputStreams.isEmpty()}
    }

    @Test fun destroyingAnOwnerWhileAFactoryRunsClosesTheStreamAndRejectsTheHandle() {
        val io = KsuIO()
        val factoryEntered = java.util.concurrent.CountDownLatch(1)
        val releaseFactory = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        val result = java.util.concurrent.atomic.AtomicReference<Result<String>>()
        val opener = Thread {
            result.set(runCatching {
                io.openInput {
                    factoryEntered.countDown(); releaseFactory.await()
                    object : ByteArrayInputStream(byteArrayOf(1)) {
                        override fun close() { closed.set(true); super.close() }
                    }
                }
            })
        }
        val destroyer = Thread { io.destroy() }
        opener.start()
        try {
            assertTrue(factoryEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            destroyer.start()
            waitFor("owner did not invalidate the page") { !io.canOpenStream() }
        } finally {
            releaseFactory.countDown()
            opener.join(3000)
            destroyer.join(3000)
        }
        waitFor("late registered stream not closed"){closed.get()}
        assertEquals(0, io.openInputStreams.size)
        assertFalse(io.canOpenStream())
        assertTrue("closed page returned a stream handle", result.get()?.exceptionOrNull() is IllegalStateException)
    }
    @Test fun destroyReturnsAndRejectsNewIoWhileFactoryOwnsTheMonitor() {
        val io=KsuIO();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val returned=java.util.concurrent.CountDownLatch(1)
        val closes=java.util.concurrent.atomic.AtomicInteger()
        val result=java.util.concurrent.atomic.AtomicReference<Result<String>>()
        val opener=Thread { result.set(runCatching { io.openInput { entered.countDown();check(release.await(3,java.util.concurrent.TimeUnit.SECONDS));object:ByteArrayInputStream(byteArrayOf(1)){override fun close(){closes.incrementAndGet();super.close()}} } }) }
        opener.start();assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
        val disposer=Thread {io.destroy();returned.countDown()};disposer.start()
        try {
            assertTrue("destroy waited for blocked stream factory",returned.await(200,java.util.concurrent.TimeUnit.MILLISECONDS))
            assertFalse(io.canOpenStream());assertEquals("",io.FileInputStream().read("late"))
        } finally {release.countDown();opener.join(3000);disposer.join(3000)}
        waitFor("late factory resource not reclaimed once"){closes.get()==1};assertTrue(io.openInputStreams.isEmpty())
        assertTrue(result.get()?.exceptionOrNull() is IllegalStateException)
    }
    @Test fun closingThePageDuringOutputCreationRejectsTheHandleAndClosesTheStream() {
        val io = KsuIO()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val result = java.util.concurrent.atomic.AtomicReference<Result<String>>()
        val opener = Thread {
            result.set(runCatching {
                io.openOutput {
                    entered.countDown()
                    check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    object : ByteArrayOutputStream() {
                        override fun close() { closes.incrementAndGet(); super.close() }
                    }
                }
            })
        }
        opener.start()
        try {
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            io.destroy()
        } finally {
            release.countDown()
            opener.join(3000)
        }
        assertTrue("closed page returned an output handle", result.get()?.exceptionOrNull() is IllegalStateException)
        waitFor("late output stream not closed once") { closes.get() == 1 }
        assertTrue(io.openOutputStreams.isEmpty())
    }

    @Test fun destroyedIoRejectsReadsAndWritesWhileBackgroundCloseIsBlocked() {
        val io=KsuIO();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val returned=java.util.concurrent.CountDownLatch(1)
        val closes=java.util.concurrent.atomic.AtomicInteger()
        io.openInputStreams["input"]=ManagedInputStream(ByteArrayInputStream(byteArrayOf(1)))
        io.openOutputStreams["output"]=object:ByteArrayOutputStream(){override fun close(){entered.countDown();check(release.await(3,java.util.concurrent.TimeUnit.SECONDS));closes.incrementAndGet();super.close()}}
        val disposer=Thread {io.destroy();io.destroy();returned.countDown()};disposer.start()
        try {
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));assertTrue("destroy waited for output close",returned.await(200,java.util.concurrent.TimeUnit.MILLISECONDS))
            assertEquals("",io.FileInputStream().read("input"));assertFalse(io.FileOutputStream().writeByte("output",1))
        } finally {release.countDown();disposer.join(3000)}
        waitFor("output resource not reclaimed once"){closes.get()==1}
    }
    @Test fun randomAccessCleanupClaimsResourceBeforeClosingIt() {
        val owner=KsuIO();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val closes=java.util.concurrent.atomic.AtomicInteger()
        val file=object:com.topjohnwu.superuser.io.SuRandomAccessFile(), java.io.DataInput by java.io.DataInputStream(ByteArrayInputStream(byteArrayOf())), java.io.DataOutput by java.io.DataOutputStream(ByteArrayOutputStream()) {
            override fun read()=-1
            override fun read(bytes:ByteArray)=-1
            override fun read(bytes:ByteArray,offset:Int,length:Int)=-1
            override fun seek(offset:Long){}
            override fun setLength(length:Long){}
            override fun length()=0L
            override fun getFilePointer()=0L
            override fun close(){entered.countDown();check(release.await(3,java.util.concurrent.TimeUnit.SECONDS));closes.incrementAndGet()}
        }
        val facade=owner.RandomAccessFile();val field=facade.javaClass.getDeclaredField("openFiles").apply{isAccessible=true}
        @Suppress("UNCHECKED_CAST") val files=field.get(facade) as java.util.concurrent.ConcurrentHashMap<String,com.topjohnwu.superuser.io.SuRandomAccessFile>;files["file"]=file
        val disposer=Thread{owner.destroy()};disposer.start();assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
        val result=java.util.concurrent.atomic.AtomicReference<Boolean?>();val closer=Thread{result.set(facade.close("file"))};closer.start()
        try {closer.join(200);assertEquals("resource still visible while cleanup owns it",false,result.get())}
        finally {release.countDown();disposer.join(3000);closer.join(3000)}
        waitFor("random access file not closed"){closes.get()>0};assertEquals(1,closes.get())
    }

    @Test fun retiredPageClosesAnInFlightInputWithoutReturningItsHandle() {
        assertPageRetirementDuringFactory(input = true)
    }

    @Test fun retiredPageClosesAnInFlightOutputWithoutReturningItsHandle() {
        assertPageRetirementDuringFactory(input = false)
    }

    private fun assertPageRetirementDuringFactory(input: Boolean) {
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val result = java.util.concurrent.atomic.AtomicReference<Result<String>>()
        val owner = KsuIO(pageCurrent = current::get)
        val opener = Thread {
            result.set(runCatching {
                if (input) owner.openInput {
                    entered.countDown()
                    check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    object : ByteArrayInputStream(byteArrayOf(1)) {
                        override fun close() { closes.incrementAndGet(); super.close() }
                    }
                } else owner.openOutput {
                    entered.countDown()
                    check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    object : ByteArrayOutputStream() {
                        override fun close() { closes.incrementAndGet(); super.close() }
                    }
                }
            })
        }
        opener.start()
        try {
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            current.set(false)
            assertFalse(owner.canOpenStream())
            release.countDown()
            opener.join(3000)

            assertFalse("Stream factory did not finish", opener.isAlive)
            assertTrue("Retired page returned a stream handle",
                result.get()?.exceptionOrNull() is IllegalStateException)
            assertEquals(1, closes.get())
            assertTrue(owner.openInputStreams.isEmpty())
            assertTrue(owner.openOutputStreams.isEmpty())
        } finally {
            release.countDown()
            opener.join(3000)
            owner.destroy()
        }
    }

    @Test fun retiredPageClosesAnInFlightInjectedShellWithoutReturningAFile() {
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val shell = PageCurrentMockShell()
        val result = java.util.concurrent.atomic.AtomicReference<Result<com.topjohnwu.superuser.io.SuFile>>()
        val owner = KsuIO(shellFactory = {
            opens.incrementAndGet()
            entered.countDown()
            check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
            shell
        }, pageCurrent = current::get)
        val opener = Thread { result.set(runCatching { owner.file("/fixture/in-flight") }) }
        opener.start()
        try {
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            current.set(false)
            release.countDown()
            opener.join(3000)

            assertFalse("Injected shell factory did not finish", opener.isAlive)
            val failure = result.get()?.exceptionOrNull()
            assertTrue(failure is java.util.concurrent.ExecutionException)
            assertTrue(failure?.cause is IllegalStateException)
            assertEquals(1, opens.get())
            assertEquals(1, shell.closes.get())
            assertThrows(IllegalStateException::class.java) { owner.file("/fixture/late") }
            assertEquals(1, opens.get())
        } finally {
            release.countDown()
            opener.join(3000)
            owner.destroy()
        }
    }

    private class PageCurrentMockShell : com.topjohnwu.superuser.Shell() {
        val closes = java.util.concurrent.atomic.AtomicInteger()
        override fun isAlive() = closes.get() == 0
        override fun getStatus() = NON_ROOT_SHELL
        override fun close() { closes.incrementAndGet() }
        override fun waitAndClose(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean {
            close()
            return true
        }
        override fun execTask(task: Task) = error("Unexpected mock shell task")
        override fun submitTask(task: Task) = error("Unexpected mock shell task")
        override fun newJob(): Job = error("Unexpected mock shell command")
    }

}
