/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
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
package com.tencent.kmm.network.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Platform seam for the KBA coroutines fork's `track` parameter.
 *
 * The fork adds a 4-parameter `launch(context, start, track, block)` overload
 * (a strict superset of upstream). Common code compiled against the fork
 * resolved plain `launch {}` calls to that overload, baking fork-only symbols
 * into every klib — which is exactly what IrLinkage-crashed iOS consumers
 * linking official coroutines. Common code therefore may not mention `track`;
 * the ohosArm64 actual (whose graph is forced to the KBA fork) is the only
 * place allowed to use it.
 */
internal expect fun CoroutineScope.transportLaunch(block: suspend CoroutineScope.() -> Unit): Job
