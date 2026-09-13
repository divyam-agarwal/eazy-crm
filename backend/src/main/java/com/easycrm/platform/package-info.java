/**
 * The shared library every service takes. Declared OPEN because it is exactly that — one library
 * consumed whole by all five services — so its 434 "non-exposed type" findings are noise:
 * TenantScopedEntity, the three error types and PageResponse are meant to be visible everywhere,
 * and carving @NamedInterface across 12 subpackages would buy nothing while the library ships as
 * one unit (M4, and spec 2026-09-13-wave-1.6-module-boundaries-design.md Part 1 W4).
 *
 * <p><b>OPEN also suppresses every cycle routing through this module — measured, all 12 of them.</b>
 * That is why ModuleDirectionArchTest exists and why ApplicationModules.verify() is NOT this
 * repo's H4 gate. Do not read a green verify() as evidence that platform depends on nothing.
 * See challenge #75.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.easycrm.platform;
