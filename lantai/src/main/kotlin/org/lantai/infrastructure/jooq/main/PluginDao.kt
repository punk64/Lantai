package org.lantai.infrastructure.jooq.main

import org.lantai.domain.model.PluginCredential
import org.lantai.domain.model.PluginGrant
import org.lantai.domain.model.PluginInstance
import org.lantai.domain.model.PluginKv
import org.lantai.domain.model.PluginKvEntry
import org.lantai.domain.model.PluginMode
import org.lantai.domain.model.PluginState
import org.lantai.domain.persistence.PluginStore
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.infrastructure.security.apitoken.TokenCipher
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.PluginCredentialRecord
import org.lantai.jooq.main.tables.records.PluginGrantRecord
import org.lantai.jooq.main.tables.records.PluginInstanceRecord
import org.lantai.language.toCurrentTimeZone
import com.github.f4b6a3.tsid.TsidCreator
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class PluginDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
  private val tokenCipher: TokenCipher,
) : SplitDslDaoBase(dslRW, dslRO),
  PluginStore {
  private val instanceTable = Tables.PLUGIN_INSTANCE
  private val grantTable = Tables.PLUGIN_GRANT
  private val credentialTable = Tables.PLUGIN_CREDENTIAL
  private val kvTable = Tables.PLUGIN_KV
  private val valueBytes = DSL.field("length(cast({0} as blob))", Int::class.java, kvTable.VALUE)
  private val totalBytes = DSL.field("sum(length(cast({0} as blob)))", Long::class.java, kvTable.VALUE)

  override fun insertInstance(instance: PluginInstance) {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    dslRW
      .insertInto(instanceTable)
      .set(instanceTable.ID, instance.id)
      .set(instanceTable.PLUGIN_ID, instance.pluginId)
      .set(instanceTable.NAME, instance.name)
      .set(instanceTable.VERSION, instance.version)
      .set(instanceTable.API_VERSION, instance.apiVersion)
      .set(instanceTable.MODE, instance.mode.name)
      .set(instanceTable.RUNTIME_KIND, instance.runtimeKind)
      .set(instanceTable.ENTRY, instance.entry)
      .set(instanceTable.ENDPOINT, instance.endpoint)
      .set(instanceTable.PORT, instance.port)
      .set(instanceTable.HOST_KEY, instance.hostKey?.let { tokenCipher.encrypt(it) })
      .set(instanceTable.TOKEN_ID, instance.tokenId)
      .set(instanceTable.STATE, instance.state.name)
      .set(instanceTable.STATUS_MESSAGE, instance.statusMessage)
      .set(instanceTable.CAPABILITIES, instance.capabilities)
      .set(instanceTable.MANIFEST, instance.manifest)
      .set(instanceTable.SETTINGS, instance.settings)
      .set(instanceTable.HOME_DIR, instance.homeDir)
      .set(instanceTable.MEMORY_MB, instance.memoryMb)
      .set(instanceTable.ENABLED, instance.enabled)
      .set(instanceTable.PID, instance.pid)
      .set(instanceTable.LAST_HEALTH_DATE, instance.lastHealthDate?.toUtc())
      .set(instanceTable.LAST_USED_DATE, instance.lastUsedDate?.toUtc())
      .set(instanceTable.CREATED_DATE, now)
      .set(instanceTable.LAST_MODIFIED_DATE, now)
      .execute()
  }

  override fun updateInstance(instance: PluginInstance) {
    dslRW
      .update(instanceTable)
      .set(instanceTable.NAME, instance.name)
      .set(instanceTable.VERSION, instance.version)
      .set(instanceTable.API_VERSION, instance.apiVersion)
      .set(instanceTable.MODE, instance.mode.name)
      .set(instanceTable.RUNTIME_KIND, instance.runtimeKind)
      .set(instanceTable.ENTRY, instance.entry)
      .set(instanceTable.ENDPOINT, instance.endpoint)
      .set(instanceTable.PORT, instance.port)
      .set(instanceTable.HOST_KEY, instance.hostKey?.let { tokenCipher.encrypt(it) })
      .set(instanceTable.TOKEN_ID, instance.tokenId)
      .set(instanceTable.STATE, instance.state.name)
      .set(instanceTable.STATUS_MESSAGE, instance.statusMessage)
      .set(instanceTable.CAPABILITIES, instance.capabilities)
      .set(instanceTable.MANIFEST, instance.manifest)
      .set(instanceTable.SETTINGS, instance.settings)
      .set(instanceTable.HOME_DIR, instance.homeDir)
      .set(instanceTable.MEMORY_MB, instance.memoryMb)
      .set(instanceTable.ENABLED, instance.enabled)
      .set(instanceTable.PID, instance.pid)
      .set(instanceTable.LAST_HEALTH_DATE, instance.lastHealthDate?.toUtc())
      .set(instanceTable.LAST_USED_DATE, instance.lastUsedDate?.toUtc())
      .set(instanceTable.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(instanceTable.ID.eq(instance.id))
      .execute()
  }

  override fun findInstanceByIdOrNull(id: String): PluginInstance? =
    dslRO
      .selectFrom(instanceTable)
      .where(instanceTable.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun findByPluginIdOrNull(pluginId: String): PluginInstance? =
    dslRO
      .selectFrom(instanceTable)
      .where(instanceTable.PLUGIN_ID.eq(pluginId))
      .fetchOne()
      ?.toDomain()

  override fun findAllInstances(): List<PluginInstance> =
    dslRO
      .selectFrom(instanceTable)
      .orderBy(instanceTable.CREATED_DATE.desc())
      .fetch()
      .map { it.toDomain() }

  override fun deleteInstance(id: String) {
    val stored = findInstanceByIdOrNull(id) ?: return
    dslRW.transaction { cfg ->
      val ctx = cfg.dsl()
      ctx.deleteFrom(grantTable).where(grantTable.PLUGIN_ID.eq(stored.pluginId)).execute()
      ctx.deleteFrom(credentialTable).where(credentialTable.PLUGIN_ID.eq(stored.pluginId)).execute()
      ctx.deleteFrom(kvTable).where(kvTable.PLUGIN_ID.eq(stored.pluginId)).execute()
      ctx.deleteFrom(instanceTable).where(instanceTable.ID.eq(id)).execute()
    }
  }

  override fun findGrantsByPluginId(pluginId: String): List<PluginGrant> =
    dslRO
      .selectFrom(grantTable)
      .where(grantTable.PLUGIN_ID.eq(pluginId))
      .orderBy(grantTable.CAPABILITY.asc())
      .fetch()
      .map { it.toDomain() }

  override fun replaceGrants(pluginId: String, grants: List<PluginGrant>) {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    dslRW.transaction { cfg ->
      val ctx = cfg.dsl()
      if (grants.isNotEmpty()) {
        ctx
          .update(grantTable)
          .set(grantTable.IS_DEFAULT, false)
          .where(grantTable.CAPABILITY.`in`(grants.map { it.capability }))
          .execute()
      }
      ctx.deleteFrom(grantTable).where(grantTable.PLUGIN_ID.eq(pluginId)).execute()
      grants.forEach { item ->
        ctx
          .insertInto(grantTable)
          .set(grantTable.ID, item.id)
          .set(grantTable.PLUGIN_ID, pluginId)
          .set(grantTable.CAPABILITY, item.capability)
          .set(grantTable.IS_DEFAULT, item.isDefault)
          .set(grantTable.GRANTED_BY, item.grantedBy)
          .set(grantTable.CREATED_DATE, now)
          .set(grantTable.LAST_MODIFIED_DATE, now)
          .execute()
      }
    }
  }

  override fun findDefaultPluginId(capability: String): String? =
    dslRO
      .select(grantTable.PLUGIN_ID)
      .from(grantTable)
      .join(instanceTable)
      .on(instanceTable.PLUGIN_ID.eq(grantTable.PLUGIN_ID))
      .where(
        grantTable.CAPABILITY
          .eq(capability)
          .and(instanceTable.ENABLED.isTrue)
          .and(instanceTable.STATE.eq(PluginState.READY.name)),
      ).orderBy(grantTable.IS_DEFAULT.desc(), instanceTable.CREATED_DATE.asc())
      .limit(1)
      .fetchOne(grantTable.PLUGIN_ID)

  override fun findProviders(capability: String): List<PluginInstance> =
    dslRO
      .select(grantTable.PLUGIN_ID)
      .from(grantTable)
      .join(instanceTable)
      .on(instanceTable.PLUGIN_ID.eq(grantTable.PLUGIN_ID))
      .where(grantTable.CAPABILITY.eq(capability).and(instanceTable.ENABLED.isTrue()))
      .orderBy(grantTable.IS_DEFAULT.desc(), instanceTable.CREATED_DATE.asc())
      .fetch()
      .mapNotNull { row -> findByPluginIdOrNull(row.value1()) }

  override fun deleteGrantsByPluginId(pluginId: String) {
    dslRW
      .deleteFrom(grantTable)
      .where(grantTable.PLUGIN_ID.eq(pluginId))
      .execute()
  }

  override fun findKvOrNull(
    pluginId: String,
    key: String,
  ): PluginKv? =
    dslRO
      .selectFrom(kvTable)
      .where(kvTable.PLUGIN_ID.eq(pluginId).and(kvTable.KEY.eq(key)))
      .fetchOne()
      ?.let {
        PluginKv(
          pluginId = it.pluginId ?: "",
          key = it.key ?: "",
          value = it.value ?: "",
          id = it.id,
          createdDate = it.createdDate.toCurrentTimeZone(),
          lastModifiedDate = it.lastModifiedDate.toCurrentTimeZone(),
        )
      }

  override fun listKv(
    pluginId: String,
    page: Int,
    size: Int,
  ): List<PluginKvEntry> =
    dslRO
      .select(kvTable.KEY, valueBytes, kvTable.LAST_MODIFIED_DATE)
      .from(kvTable)
      .where(kvTable.PLUGIN_ID.eq(pluginId))
      .orderBy(kvTable.KEY.asc())
      .limit(size)
      .offset(page * size)
      .fetch()
      .map {
        PluginKvEntry(
          key = it[kvTable.KEY] ?: "",
          size = (it[valueBytes] ?: 0).toLong(),
          lastModifiedDate = it[kvTable.LAST_MODIFIED_DATE]?.toCurrentTimeZone(),
        )
      }

  override fun countKv(pluginId: String): Int =
    dslRO
      .selectCount()
      .from(kvTable)
      .where(kvTable.PLUGIN_ID.eq(pluginId))
      .fetchOne(0, Int::class.java) ?: 0

  override fun upsertKv(
    pluginId: String,
    key: String,
    value: String,
  ) {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    dslRW
      .insertInto(kvTable)
      .set(kvTable.ID, TsidCreator.getTsid256().toString())
      .set(kvTable.PLUGIN_ID, pluginId)
      .set(kvTable.KEY, key)
      .set(kvTable.VALUE, value)
      .set(kvTable.CREATED_DATE, now)
      .set(kvTable.LAST_MODIFIED_DATE, now)
      .onConflict(kvTable.PLUGIN_ID, kvTable.KEY)
      .doUpdate()
      .set(kvTable.VALUE, value)
      .set(kvTable.LAST_MODIFIED_DATE, now)
      .execute()
  }

  override fun deleteKv(
    pluginId: String,
    key: String,
  ): Boolean =
    dslRW
      .deleteFrom(kvTable)
      .where(kvTable.PLUGIN_ID.eq(pluginId).and(kvTable.KEY.eq(key)))
      .execute() > 0

  override fun kvTotalBytes(pluginId: String): Long =
    dslRO
      .select(totalBytes)
      .from(kvTable)
      .where(kvTable.PLUGIN_ID.eq(pluginId))
      .fetchOne(0, Long::class.java) ?: 0L

  override fun listCredentials(pluginId: String): List<PluginCredential> =
    dslRO
      .selectFrom(credentialTable)
      .where(credentialTable.PLUGIN_ID.eq(pluginId))
      .orderBy(credentialTable.NAME.asc())
      .fetch()
      .map { it.toDomain() }

  override fun findCredentialOrNull(
    pluginId: String,
    name: String,
  ): PluginCredential? =
    dslRO
      .selectFrom(credentialTable)
      .where(credentialTable.PLUGIN_ID.eq(pluginId).and(credentialTable.NAME.eq(name)))
      .fetchOne()
      ?.toDomain()

  override fun upsertCredential(
    pluginId: String,
    name: String,
    value: String,
  ) {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    dslRW
      .insertInto(credentialTable)
      .set(credentialTable.ID, TsidCreator.getTsid256().toString())
      .set(credentialTable.PLUGIN_ID, pluginId)
      .set(credentialTable.NAME, name)
      .set(credentialTable.VALUE, value)
      .set(credentialTable.CREATED_DATE, now)
      .set(credentialTable.LAST_MODIFIED_DATE, now)
      .onConflict(credentialTable.PLUGIN_ID, credentialTable.NAME)
      .doUpdate()
      .set(credentialTable.VALUE, value)
      .set(credentialTable.LAST_MODIFIED_DATE, now)
      .execute()
  }

  override fun deleteCredential(
    pluginId: String,
    name: String,
  ): Boolean =
    dslRW
      .deleteFrom(credentialTable)
      .where(credentialTable.PLUGIN_ID.eq(pluginId).and(credentialTable.NAME.eq(name)))
      .execute() > 0

  private fun PluginCredentialRecord.toDomain() =
    PluginCredential(
      pluginId = pluginId ?: "",
      name = name ?: "",
      value = value ?: "",
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )


  private fun LocalDateTime.toUtc(): LocalDateTime = atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of("Z")).toLocalDateTime()

  private fun PluginInstanceRecord.toDomain() =
    PluginInstance(
      pluginId = pluginId ?: "",
      name = name ?: "",
      version = version ?: "",
      apiVersion = apiVersion ?: 2,
      mode = runCatching { PluginMode.valueOf(mode) }.getOrDefault(PluginMode.EXTERNAL),
      runtimeKind = runtimeKind ?: "",
      entry = entry ?: "",
      homeDir = homeDir ?: "",
      endpoint = endpoint,
      port = port,
      hostKey = hostKey?.let { tokenCipher.decrypt(it) ?: it },
      tokenId = tokenId,
      state = runCatching { PluginState.valueOf(state) }.getOrDefault(PluginState.PENDING),
      statusMessage = statusMessage,
      capabilities = capabilities ?: "[]",
      manifest = manifest ?: "{}",
      settings = settings ?: "{}",
      memoryMb = memoryMb ?: 0,
      enabled = enabled ?: true,
      pid = pid,
      lastHealthDate = lastHealthDate?.toCurrentTimeZone(),
      lastUsedDate = lastUsedDate?.toCurrentTimeZone(),
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )

  private fun PluginGrantRecord.toDomain() =
    PluginGrant(
      pluginId = pluginId ?: "",
      capability = capability ?: "",
      isDefault = isDefault ?: false,
      grantedBy = grantedBy,
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
