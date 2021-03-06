/*
 * Copyright (C) 2021 Gabor Varadi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zhuinden.simplestackcomposeintegration.core

import android.annotation.SuppressLint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMaxBy
import com.zhuinden.simplestack.*
import com.zhuinden.simplestackcomposeintegration.core.AnimatingComposeStateChanger.AnimationConfiguration.CustomComposableTransitions.ComposableTransition

/**
 * Composition local to access the key within screens.
 */
val LocalComposeKey =
    staticCompositionLocalOf<DefaultComposeKey> { throw IllegalStateException("Key does not exist in this composable scope") }

/**
 * A key that receives a modifier and provides for the compose key composition local.
 *
 * Please note that it is not Parcelable by default.
 */
abstract class DefaultComposeKey {
    @Composable
    fun RenderComposable(modifier: Modifier = Modifier) {
        val key = this

        CompositionLocalProvider(LocalComposeKey provides (key)) {
            key(key) {
                ScreenComposable(modifier = modifier)
            }
        }
    }

    @Composable
    protected abstract fun ScreenComposable(modifier: Modifier = Modifier)
}

/**
 * A state changer that allows switching between composables, animating the transition.
 */
class AnimatingComposeStateChanger(
    private val animationConfiguration: AnimationConfiguration = AnimationConfiguration()
): AsyncStateChanger.NavigationHandler {
    private var backstackState by mutableStateOf(BackstackState(animationConfiguration = animationConfiguration))

    override fun onNavigationEvent(
        stateChange: StateChange,
        completionCallback: StateChanger.Callback
    ) {
        this.backstackState =
            BackstackState(
                animationConfiguration = animationConfiguration,
                stateChange = stateChange,
                callback = completionCallback,
            )
    }

    /**
     * Configuration for the screen switching animations.
     */
    class AnimationConfiguration(
        val animationSpec: FiniteAnimationSpec<Float> = TweenSpec(250, 0, LinearEasing),
        val customComposableTransitions: CustomComposableTransitions = CustomComposableTransitions()
    ) {
        /**
         * Allows customizing the screen switching animations.
         */
        class CustomComposableTransitions(
            /**
             * The previous transition.
             */
            val previousComposableTransition: ComposableTransition =
                ComposableTransition { modifier, stateChange, fullWidth, fullHeight, animationProgress ->
                    modifier.then(
                        when (stateChange.direction) {
                            StateChange.FORWARD -> Modifier.graphicsLayer(translationX = 0 + (-1) * fullWidth * animationProgress)
                            StateChange.BACKWARD -> Modifier.graphicsLayer(translationX = 0 + fullWidth * animationProgress)
                            else /* REPLACE */ -> Modifier.graphicsLayer(alpha = (1 - animationProgress))
                        }
                    )
                },
            /**
             * The new transition.
             */
            val newComposableTransition: ComposableTransition =
                ComposableTransition { modifier, stateChange, fullWidth, fullHeight, animationProgress ->
                    modifier.then(
                        when (stateChange.direction) {
                            StateChange.FORWARD -> Modifier.graphicsLayer(translationX = fullWidth + (-1) * fullWidth * animationProgress)
                            StateChange.BACKWARD -> Modifier.graphicsLayer(translationX = -1 * fullWidth + fullWidth * animationProgress)
                            else /* REPLACE */ -> Modifier.graphicsLayer(alpha = 0 + animationProgress)
                        }
                    )
                },
        ) {
            /**
             * An interface to describe transition of a composables.
             */
            fun interface ComposableTransition {
                @SuppressLint("ModifierFactoryExtensionFunction")
                fun animateComposable(modifier: Modifier, stateChange: StateChange, fullWidth: Int, fullHeight: Int, animationProgress: Float): Modifier
            }
        }
    }

    private data class BackstackState(
        private val animationConfiguration: AnimationConfiguration,
        private val stateChange: StateChange? = null,
        private val callback: StateChanger.Callback? = null,
    ) {
        @Composable
        fun RenderScreen(modifier: Modifier = Modifier) {
            val stateChange = stateChange ?: return
            val callback = callback ?: return

            var completionCallback by remember { mutableStateOf<StateChanger.Callback?>(null) }

            val topNewKey by rememberUpdatedState(newValue = stateChange.topNewKey<DefaultComposeKey>())
            val topPreviousKey by rememberUpdatedState(newValue = stateChange.topPreviousKey<DefaultComposeKey>())

            var isAnimating by remember { mutableStateOf(false) }

            val lerping = remember { Animatable(0.0f) }

            var animationProgress by remember { mutableStateOf(0.0f) }

            var initialization by remember { mutableStateOf(true) }

            if (completionCallback !== callback) {
                completionCallback = callback

                if (topPreviousKey != null) {
                    initialization = false

                    animationProgress = 0.0f
                    isAnimating = true
                } else {
                    initialization = true
                }
            }

            var fullWidth by remember { mutableStateOf(0) }
            var fullHeight by remember { mutableStateOf(0) }

            val measurePolicy = MeasurePolicy { measurables, constraints ->
                val placeables = measurables.fastMap { it.measure(constraints) }
                val maxWidth = placeables.fastMaxBy { it.width }?.width ?: 0
                val maxHeight = placeables.fastMaxBy { it.height }?.height ?: 0

                if (fullWidth == 0 && maxWidth != 0) {
                    fullWidth = maxWidth
                }

                if (fullHeight == 0 && maxHeight != 0) {
                    fullHeight = maxHeight
                }

                layout(maxWidth, maxHeight) {
                    placeables.fastForEach { placeable ->
                        placeable.place(0, 0)
                    }
                }
            }

            val keySlot1 by rememberUpdatedState(when {
                initialization -> topNewKey
                else -> topPreviousKey
            })

            val keySlot2 by rememberUpdatedState(when {
                initialization -> topPreviousKey
                else -> topNewKey
            })

            val previousTransition = animationConfiguration.customComposableTransitions.previousComposableTransition
            val newTransition = animationConfiguration.customComposableTransitions.newComposableTransition

            Layout(
                content = {
                    if (initialization || isAnimating) {
                        Box(
                            modifier = when {
                                !isAnimating || initialization -> modifier
                                else -> previousTransition.animateComposable(modifier, stateChange, fullWidth, fullHeight, animationProgress)
                            }
                        ) {
                            key(keySlot1) {
                                keySlot1?.RenderComposable(modifier)
                            }
                        }
                    }

                    if (!initialization || isAnimating) {
                        Box(
                            modifier = when {
                                !isAnimating || initialization -> modifier
                                else -> newTransition.animateComposable(modifier, stateChange, fullWidth, fullHeight, animationProgress)
                            }
                        ) {
                            key(keySlot2) {
                                keySlot2?.RenderComposable(modifier)
                            }
                        }
                    }
                },
                measurePolicy = measurePolicy,
            )

            LaunchedEffect(key1 = completionCallback, block = {
                if (topPreviousKey != null) {
                    lerping.animateTo(1.0f, animationConfiguration.animationSpec) {
                        animationProgress = this.value
                    }
                    isAnimating = false
                    lerping.snapTo(0f)
                }
                completionCallback!!.stateChangeComplete()
            })
        }
    }

    @Composable
    fun RenderScreen(modifier: Modifier = Modifier) {
        LocalBackstack.current // force `BackstackProvider` to be set

        backstackState.RenderScreen(modifier)
    }
}

/**
 * A state changer that allows switching between composables, animating the transition.
 */
class SimpleComposeStateChanger: SimpleStateChanger.NavigationHandler {
    private var backstackState by mutableStateOf(BackstackState())

    private data class BackstackState(
        private val stateChange: StateChange? = null,
    ) {
        @Composable
        fun RenderScreen(modifier: Modifier = Modifier) {
            val stateChange = stateChange ?: return

            val topNewKey = stateChange.topNewKey<DefaultComposeKey>()

            key(topNewKey) {
                topNewKey.RenderComposable(modifier)
            }
        }
    }

    @Composable
    fun RenderScreen(modifier: Modifier = Modifier) {
        LocalBackstack.current // force `BackstackProvider` to be set

        backstackState.RenderScreen(modifier)
    }

    override fun onNavigationEvent(stateChange: StateChange) {
        backstackState = BackstackState(stateChange)
    }
}


/**
 * Composition local to access the Backstack within screens.
 */
val LocalBackstack =
    staticCompositionLocalOf<Backstack> { throw IllegalStateException("You must ensure that the BackstackProvider provides the backstack, but it currently doesn't exist.") }

/**
 * Provider for the backstack composition local.
 */
@Composable
fun BackstackProvider(backstack: Backstack, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBackstack provides (backstack)) {
        content()
    }
}