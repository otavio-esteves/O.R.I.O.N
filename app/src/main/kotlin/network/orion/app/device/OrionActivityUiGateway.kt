package network.orion.app.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.orion.core.queries.activity.*
import network.orion.data.database.OrionDatabase
import network.orion.data.events.OrionActivityQuery

/** The supplier is MAIN runtime-owned and returns null until minimum storage readiness. */
internal class OrionActivityUiGateway(private val database: () -> OrionDatabase?) : ActivityGateway {
    override suspend fun get(request: GetActivity): ActivityPage? = withContext(Dispatchers.IO) {
        database()?.let { OrionActivityQuery(it).get(request) }
    }
}
