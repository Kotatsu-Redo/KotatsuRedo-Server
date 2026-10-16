package io.kotatsuredo.server.ops

import java.io.File
import java.lang.management.ManagementFactory

/**
 * What the process is costing the box, for the panel's health view.
 *
 * The question this answers is the one asked at 2am: is the server slow because it is out of memory,
 * out of CPU, or out of database connections? Answering it used to need an ssh session, `docker
 * stats` and a JVM the operator could not see into.
 *
 * Sampled on demand rather than collected: every figure here is a single management-bean read, the
 * view is opened by one moderator now and then, and a background sampler would be machinery for a
 * number nobody is watching between visits.
 */
object ResourceUsage {

	data class Snapshot(
		val heapUsedMb: Long,
		val heapMaxMb: Long,
		/** Resident set size: what the container is actually charged for. Null off Linux. */
		val rssMb: Long?,
		/** This process's share of the machine, 0..1. Null when the JVM will not say. */
		val cpuProcess: Double?,
		/** The whole machine, 0..1, so a noisy neighbour is distinguishable from our own load. */
		val cpuSystem: Double?,
		val processors: Int,
		val threads: Int,
		/** Database connections in use and waiting, when the pool reports them. */
		val dbActive: Int?,
		val dbIdle: Int?,
		val dbWaiting: Int?,
	) {
		val heapPercent: Double get() = if (heapMaxMb <= 0) 0.0 else heapUsedMb.toDouble() / heapMaxMb
	}

	/** Set once at startup so the pool can be read without threading it through every caller. */
	@Volatile
	var poolStats: (() -> Triple<Int, Int, Int>)? = null

	fun snapshot(): Snapshot {
		val memory = ManagementFactory.getMemoryMXBean().heapMemoryUsage
		val operatingSystem = ManagementFactory.getOperatingSystemMXBean()
		val pool = runCatching { poolStats?.invoke() }.getOrNull()

		return Snapshot(
			heapUsedMb = memory.used / MB,
			// `max` is -1 when unbounded; `committed` is then what the JVM has actually taken.
			heapMaxMb = (if (memory.max > 0) memory.max else memory.committed) / MB,
			rssMb = readRssMb(),
			cpuProcess = cpuLoad(operatingSystem, "getProcessCpuLoad"),
			cpuSystem = cpuLoad(operatingSystem, "getCpuLoad") ?: cpuLoad(operatingSystem, "getSystemCpuLoad"),
			processors = operatingSystem.availableProcessors,
			threads = ManagementFactory.getThreadMXBean().threadCount,
			dbActive = pool?.first,
			dbIdle = pool?.second,
			dbWaiting = pool?.third,
		)
	}

	/**
	 * `com.sun.management.OperatingSystemMXBean` carries the CPU figures, and is not on every JVM.
	 * Reflection keeps this compiling and running anywhere rather than pinning the build to a vendor.
	 */
	private fun cpuLoad(bean: Any, method: String): Double? = runCatching {
		val value = bean.javaClass.getMethod(method).apply { isAccessible = true }.invoke(bean) as? Double
		value?.takeIf { it >= 0.0 }
	}.getOrNull()

	/**
	 * Resident memory from the kernel. The JVM's own heap figure hides everything else the process
	 * holds - thread stacks, metaspace, buffers - which is exactly what an OOM kill counts.
	 */
	private fun readRssMb(): Long? = runCatching {
		File("/proc/self/status").takeIf { it.canRead() }
			?.useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") } }
			?.filter(Char::isDigit)
			?.toLongOrNull()
			?.div(1024) // the kernel reports kB
	}.getOrNull()

	private const val MB = 1024L * 1024L
}
