package com.coloros16.liquidglass.hook

import java.lang.reflect.Method

/**
 * 进度方法签名候选：按参数个数从多到少排列，逐个尝试取声明方法。
 *
 * 宿主同一方法在不同版本可能带或不带协程续体参数，只试一种会让另一档整条链静默失效。
 * 纯 JVM，可用假 Class 单测。
 */
object MethodSignaturePicker {

    /**
     * 依次尝试候选签名，返回第一个命中的方法；全不中返回 null。
     *
     * [lookup] 由调用方提供（通常是 `Class::getDeclaredMethod`），便于单测注入。
     */
    fun pick(
        name: String,
        candidates: List<Array<Class<*>>>,
        lookup: (String, Array<Class<*>>) -> Method?,
    ): Method? {
        for (params in candidates) {
            val m = try {
                lookup(name, params)
            } catch (t: Throwable) {
                null
            }
            if (m != null) return m
        }
        return null
    }

    /** 命中的候选下标；全不中返回 -1（供诊断日志标明走了哪一档）。 */
    fun pickIndex(
        name: String,
        candidates: List<Array<Class<*>>>,
        lookup: (String, Array<Class<*>>) -> Method?,
    ): Int {
        for ((index, params) in candidates.withIndex()) {
            val m = try {
                lookup(name, params)
            } catch (t: Throwable) {
                null
            }
            if (m != null) return index
        }
        return -1
    }

    /** 一个候选：方法名 + 参数表（名字随档不同的场景用）。 */
    class Candidate(val name: String, val params: Array<Class<*>>)

    /**
     * 名字与参数都可变的候选链：返回命中的候选，全不中返回 null。
     *
     * 用于同一语义在不同档被编译成不同方法名（如实例方法 vs 静态 nest 桥）。
     */
    fun pickNamed(
        candidates: List<Candidate>,
        lookup: (String, Array<Class<*>>) -> Method?,
    ): Candidate? {
        for (candidate in candidates) {
            val m = try {
                lookup(candidate.name, candidate.params)
            } catch (t: Throwable) {
                null
            }
            if (m != null) return candidate
        }
        return null
    }
}