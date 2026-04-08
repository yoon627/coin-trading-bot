package com.trading.bot.auth

import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtProvider: JwtProvider,
) {
    @PostMapping("/register")
    suspend fun register(@RequestBody req: AuthRequest): AuthResponse {
        val existing = userRepository.findByUsername(req.username).awaitSingleOrNull()
        if (existing != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Username already exists")
        }
        if (req.username.length < 3 || req.password.length < 6) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Username min 3 chars, password min 6 chars")
        }
        val user = userRepository.save(
            UserEntity(
                username = req.username,
                password = passwordEncoder.encode(req.password),
                upbitAccessKey = req.upbitAccessKey,
                upbitSecretKey = req.upbitSecretKey,
            )
        ).awaitSingle()
        val token = jwtProvider.generateToken(user.id!!, user.username)
        return AuthResponse(token = token, username = user.username)
    }

    @PostMapping("/login")
    suspend fun login(@RequestBody req: AuthRequest): AuthResponse {
        val user = userRepository.findByUsername(req.username).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        if (!passwordEncoder.matches(req.password, user.password)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        }
        val token = jwtProvider.generateToken(user.id!!, user.username)
        return AuthResponse(token = token, username = user.username)
    }
}

data class AuthRequest(
    val username: String,
    val password: String,
    val upbitAccessKey: String? = null,
    val upbitSecretKey: String? = null,
)
data class AuthResponse(val token: String, val username: String)
