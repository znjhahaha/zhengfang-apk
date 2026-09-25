package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig

/** Historical Kotlin sample oracle only. Production factories select TS plugins;
 * plugins/tests/protocol-*.test.mjs execute the migrated implementation. */
internal object LegacyProtocolFixtures {
    fun gateway(school: SchoolConfig): AcademicPasswordLoginGateway {
        val sessions = AcademicSessionStore()
        return AcademicPasswordLoginGateway(school) { selected, account ->
            val session = sessions.session(selected.id, account, selected.fullBasePath)
            val transport = AcademicHttpTransport(selected, session)
            when (selected.academicType()) {
                AcademicSystem.ZF -> ZfAcademicAdapter(selected, session, transport)
                AcademicSystem.ZF_OLD -> ZfOldAcademicAdapter(selected, session, transport)
                AcademicSystem.QZ -> QzAcademicAdapter(selected, session, transport)
                AcademicSystem.QZ_OLD -> QzOldAcademicAdapter(selected, session, transport)
                else -> throw AcademicException(AcademicStatus.UNSUPPORTED, "No historical protocol fixture for ${selected.academicType()}")
            }
        }
    }
}
