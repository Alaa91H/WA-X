package com.wax.module.modern

import java.lang.reflect.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Regression for #455: a nested WaContactData JID must be read from its real owner. */
class ModernContactDataOwnerTest {
    private open class Jid(val value: String)
    private class PhoneJid(value: String) : Jid(value)
    private class Data(val jid: Jid)
    private class Contact(val data: Data)
    private class NullableContact(val data: Data?)
    private class DirectContact(val phoneJid: PhoneJid)
    private class MissingData(val unused: String)
    private class TwoData(val one: Data, val two: Data)
    private class MissingJid(val unused: String)
    private class MissingJidContact(val data: MissingJid)
    private class TwoJids(val one: Jid, val two: Jid)
    private class TwoPhone(val one: PhoneJid, val two: PhoneJid)

    @Test fun nestedDataOwnerIsUnwrappedBeforeJidRead() {
        val plan = plan(Contact::class.java)
        assertEquals(ModernContactAccess.Outcome.AVAILABLE, plan.outcome)
        val jid = Jid("opaque-test-id")
        val contact = Contact(Data(jid))
        val access = access(Contact::class.java, plan)
        assertSame(contact.data, access.contactData(contact))
        assertSame(jid, access.userJid(contact))
        assertNull(access.userJid(MissingData("wrong class")))
    }

    @Test fun directOwnerDoesNotAttemptToUnwrapData() {
        val plan = plan(DirectContact::class.java)
        assertEquals(ModernContactAccess.Outcome.AVAILABLE, plan.outcome)
        val contact = DirectContact(PhoneJid("opaque-test-id"))
        val access = access(DirectContact::class.java, plan)
        assertSame(contact, access.contactData(contact))
        assertSame(contact.phoneJid, access.userJid(contact))
    }

    @Test fun nullableNestedDataFailsClosed() {
        val plan = plan(NullableContact::class.java)
        val contact = NullableContact(null)
        val access = access(NullableContact::class.java, plan)
        assertEquals(ModernContactAccess.Outcome.AVAILABLE, plan.outcome)
        assertNull(access.contactData(contact))
        assertNull(access.userJid(contact))
    }

    @Test fun missingNestedDataFieldIsReported() {
        assertEquals(ModernContactAccess.Outcome.CONTACT_DATA_FIELD_MISSING, plan(MissingData::class.java).outcome)
    }

    @Test fun multipleNestedDataFieldsAreRejected() {
        assertEquals(ModernContactAccess.Outcome.CONTACT_DATA_FIELD_AMBIGUOUS, plan(TwoData::class.java).outcome)
    }

    @Test fun missingJidFieldIsReported() {
        assertEquals(
            ModernContactAccess.Outcome.USER_JID_FIELD_MISSING,
            plan(MissingJidContact::class.java, MissingJid::class.java).outcome,
        )
    }

    @Test fun multipleJidFieldsAreRejected() {
        assertEquals(
            ModernContactAccess.Outcome.USER_JID_FIELD_AMBIGUOUS,
            plan(TwoJidsContact::class.java, TwoJids::class.java).outcome,
        )
    }

    private class TwoJidsContact(val data: TwoJids)

    @Test fun multiplePhoneFieldsAreRejectedBeforeChoosingAnOwner() {
        assertEquals(ModernContactAccess.Outcome.PHONE_JID_FIELD_AMBIGUOUS, plan(TwoPhone::class.java).outcome)
    }

    private fun plan(
        contactClass: Class<*>,
        dataClass: Class<*> = Data::class.java,
    ) = ModernContactAccess.selectFieldPlan(
        contactClass,
        dataClass,
        Jid::class.java,
        PhoneJid::class.java,
    )

    private fun access(
        contactClass: Class<*>,
        plan: ModernContactAccess.Companion.FieldPlan,
    ): ModernContactAccess {
        val dataField = plan.contactDataField
        val jidField = requireNotNull(plan.jidField)
        dataField?.isAccessible = true
        jidField.isAccessible = true
        val constructor = ModernContactAccess::class.java.getDeclaredConstructor(
            Class::class.java,
            Field::class.java,
            Class::class.java,
            Class::class.java,
            Field::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(contactClass, dataField, Jid::class.java, PhoneJid::class.java, jidField)
    }
}
