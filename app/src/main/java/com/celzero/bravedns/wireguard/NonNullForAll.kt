/*
 * Copyright 2023 RethinkDNS and its authors
 *
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.wireguard

import androidx.annotation.RestrictTo
import java.lang.annotation.ElementType
import javax.annotation.Nonnull
import javax.annotation.meta.TypeQualifierDefault

/**
 * This annotation can be applied to a package, class or method to indicate that all class fields
 * and method parameters and return values in that element are nonnull by default unless overridden.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@Nonnull
@TypeQualifierDefault(ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class NonNullForAll
