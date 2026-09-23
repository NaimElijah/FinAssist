import { describe, expect, it } from 'vitest';

import { writeAccessFor } from './access';

describe('writeAccessFor', () => {
    it('Given_the_owner_role_When_mapped_Then_write_access_is_granted', () => {
        expect(writeAccessFor(['owner'])).toEqual({ canWrite: true });
    });

    it('Given_the_viewer_role_alone_When_mapped_Then_write_access_is_refused', () => {
        expect(writeAccessFor(['viewer'])).toEqual({ canWrite: false });
    });

    it('Given_the_assistant_role_When_mapped_Then_write_access_is_refused', () => {
        // The assistant is a service principal that never signs into the browser, but the mapping
        // still refuses it rather than assuming a role this client has never seen is safe.
        expect(writeAccessFor(['assistant'])).toEqual({ canWrite: false });
    });

    it('Given_an_unrecognised_role_When_mapped_Then_write_access_is_refused', () => {
        expect(writeAccessFor(['someone-made-this-up'])).toEqual({ canWrite: false });
    });

    it('Given_no_roles_When_mapped_Then_write_access_is_refused', () => {
        expect(writeAccessFor([])).toEqual({ canWrite: false });
    });

    it('Given_both_owner_and_viewer_When_mapped_Then_the_wider_permission_wins', () => {
        // Mirrors sessionApi.ts's own isReadOnly rule: a session carrying both is an owner who
        // also happens to hold the viewer role.
        expect(writeAccessFor(['viewer', 'owner'])).toEqual({ canWrite: true });
    });
});
