/*
 * Copyright (C) 2023, jones
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package jones.sonar.velocity.fallback

import com.github.benmanes.caffeine.cache.Caffeine
import jones.sonar.api.Sonar
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class FallbackAttemptLimiter {
    companion object {
        private val CHECKS_PER_MINUTE = Caffeine.newBuilder()
            .expireAfterWrite(1L, TimeUnit.MINUTES)
            .build<InetAddress, Int>()

        @JvmStatic
        fun shouldAllow(inetAddress: InetAddress): Boolean {
            val newCount = CHECKS_PER_MINUTE.asMap().getOrDefault(inetAddress, 0) + 1

            if (!CHECKS_PER_MINUTE.asMap().containsKey(inetAddress)) {
                CHECKS_PER_MINUTE.put(inetAddress, newCount)
            } else {
                CHECKS_PER_MINUTE.asMap().replace(inetAddress, newCount)
            }

            return newCount <= Sonar.get().config.VERIFICATIONS_PER_MINUTE
        }
    }
}