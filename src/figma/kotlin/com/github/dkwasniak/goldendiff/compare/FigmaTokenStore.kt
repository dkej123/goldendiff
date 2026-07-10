package com.github.dkwasniak.goldendiff.compare

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.project.Project
import java.security.MessageDigest

object FigmaTokenStore {

    fun get(project: Project): String? =
        PasswordSafe.instance.getPassword(attributes(project))?.takeIf { it.isNotBlank() }

    fun set(project: Project, token: String?) {
        val normalized = token?.trim().orEmpty()
        val credentials = normalized.takeIf { it.isNotBlank() }?.let { Credentials(USER_NAME, it) }
        PasswordSafe.instance.set(attributes(project), credentials)
    }

    private fun attributes(project: Project): CredentialAttributes =
        CredentialAttributes("Golden Diff Figma Token (${projectKey(project)})", USER_NAME)

    private fun projectKey(project: Project): String {
        val value = project.basePath ?: project.name
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private const val USER_NAME = "figma"
}
