package com.rickclephas.kmp.nativecoroutines.compiler

import com.rickclephas.kmp.nativecoroutines.compiler.utils.*
import com.rickclephas.kmp.nativecoroutines.compiler.utils.isCoroutinesFunction
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.resolve.extensions.SyntheticResolveExtension
import org.jetbrains.kotlin.resolve.lazy.descriptors.AbstractLazyMemberScope
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassMemberScope
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import java.lang.Integer.min

internal class KmpNativeCoroutinesSyntheticResolveExtension(
    private val nameGenerator: NameGenerator
): SyntheticResolveExtension {

    // We need the user declared functions. Unfortunately there doesn't seem to be an official way for that.
    // Instead we'll use reflection to use the same code the compiler is using.
    // https://github.com/JetBrains/kotlin/blob/fe8f7cfcae3b33ba7ee5d06cd45e5e68f3c421a8/compiler/frontend/src/org/jetbrains/kotlin/resolve/lazy/descriptors/LazyClassMemberScope.kt#L64
    @Suppress("UNCHECKED_CAST")
    private fun ClassDescriptor.getDeclarations(): List<DeclarationDescriptor> {
        val memberScope = unsubstitutedMemberScope
        if (memberScope !is LazyClassMemberScope) return emptyList()
        return AbstractLazyMemberScope::class.java.declaredMethods
            .first { it.name == "computeDescriptorsFromDeclaredElements" }
            .apply { isAccessible = true }
            .invoke(memberScope, DescriptorKindFilter.ALL, MemberScope.ALL_NAME_FILTER,
                NoLookupLocation.WHEN_GET_ALL_DESCRIPTORS) as List<DeclarationDescriptor>
    }

    // Checking some return types will recursively call our synthetic resolve extension again.
    // We'll use the stacktrace to check for this and prevent infinite loops.
    private fun isRecursiveCall(): Boolean {
        val stackTrace = Throwable().stackTrace
        val currentElement = stackTrace.getOrNull(1) ?: return false
        // The original call should be in the first 70 elements
        for (i in 2 until min(70, stackTrace.size)) {
            if (stackTrace[i].className == currentElement.className)
                return true
        }
        return false
    }

    private fun ClassDescriptor.getDeclaredFunctions(): List<SimpleFunctionDescriptor> =
        getDeclarations().mapNotNull { it as? SimpleFunctionDescriptor }

    override fun getSyntheticFunctionNames(thisDescriptor: ClassDescriptor): List<Name> =
        if (isRecursiveCall()) emptyList()
        else thisDescriptor.getDeclaredFunctions()
            .filter { it.isCoroutinesFunction }
            .map { nameGenerator.createNativeName(it.name) }
            .distinct()

    override fun generateSyntheticMethods(
        thisDescriptor: ClassDescriptor,
        name: Name,
        bindingContext: BindingContext,
        fromSupertypes: List<SimpleFunctionDescriptor>,
        result: MutableCollection<SimpleFunctionDescriptor>
    ) {
        if (!nameGenerator.isNativeName(name) || result.isNotEmpty()) return
        val originalName = nameGenerator.createOriginalName(name)
        result += thisDescriptor.getDeclaredFunctions()
            .filter { it.name == originalName && it.isCoroutinesFunction }
            .map { createNativeFunctionDescriptor(thisDescriptor, it, name) }
    }

    private fun createNativeFunctionDescriptor(
        thisDescriptor: ClassDescriptor,
        coroutinesFunctionDescriptor: SimpleFunctionDescriptor,
        name: Name
    ): SimpleFunctionDescriptor {
        var returnType = coroutinesFunctionDescriptor.returnType
            ?: throw IllegalStateException("Coroutines function doesn't have a return type")

        // Convert Flow types to NativeFlow
        val flowValueType = coroutinesFunctionDescriptor.getFlowValueTypeOrNull()
        if (flowValueType != null)
            returnType = thisDescriptor.module.getExpandedNativeFlowType(flowValueType.type)

        // Convert suspend function to NativeSuspend
        if (coroutinesFunctionDescriptor.isSuspend)
            returnType = thisDescriptor.module.getExpandedNativeSuspendType(returnType)

        return SimpleFunctionDescriptorImpl.create(
            thisDescriptor,
            Annotations.EMPTY,
            name,
            CallableMemberDescriptor.Kind.SYNTHESIZED,
            SourceElement.NO_SOURCE
        ).apply {
            initialize(
                coroutinesFunctionDescriptor.extensionReceiverParameter,
                coroutinesFunctionDescriptor.dispatchReceiverParameter,
                emptyList(),
                coroutinesFunctionDescriptor.valueParameters.map {
                    it.copy(this, it.name, it.index)
                },
                returnType,
                Modality.FINAL,
                coroutinesFunctionDescriptor.visibility
            )
        }
    }
}