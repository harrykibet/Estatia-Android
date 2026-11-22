# Accessibility Guidelines

## Purpose and scope

Estatia should be usable by people with a broad range of vision, hearing, motor, cognitive, and situational needs. Accessibility is part of feature design, implementation, testing, and review—not a final visual polish step.

These guidelines apply to Android application UI, including Jetpack Compose screens, navigation, forms, dialogs, media controls, maps, and shared design-system components.

The project uses the principles of WCAG 2.2 Level AA as a design and review target where they apply to a native Android experience. This document defines expectations for new and changed work; it is not a claim that every existing screen has been audited or certified as conformant.

## Interaction and semantics

- Every interactive control must expose a meaningful accessible name and role. A person using TalkBack or another assistive technology must be able to understand what the control does without relying on its visual appearance.
- In Jetpack Compose, use the semantics system intentionally. Give icon-only buttons an appropriate label, expose state such as selected, checked, expanded, and disabled, and avoid creating misleading or duplicate announcements.
- Provide useful descriptions for informative images. Mark purely decorative images as decorative so assistive technology can skip them.
- Group related content and controls logically. Use headings and semantic grouping where they improve navigation, and ensure custom components expose the same meaning and actions as standard controls.
- Ensure dialogs, sheets, menus, and navigation transitions have sensible focus behavior. Users must be able to identify the current context and return to the expected place.
- Do not communicate an action, status, error, or selection through color, shape, animation, or position alone. Include text, semantics, icons with labels, or another perceivable signal.

## Touch, keyboard, and alternative input

- Make touch targets large enough to activate reliably. Follow Android's 48 dp minimum touch-target guidance for interactive controls; avoid placing small controls so close together that they are difficult to distinguish or activate.
- Support external keyboard navigation where the platform and component allow it. Focus order should follow the visual and logical reading order, and the focused element must be visibly identifiable.
- Do not make a core task depend exclusively on a complex gesture. Provide an accessible button, menu action, or other alternative for swipe, drag, pinch, and media interactions.
- Check that important flows remain usable with TalkBack, Switch Access, and other relevant assistive input methods.

## Text, color, and visual presentation

- Support Android font scaling and display-size settings. Content must not be clipped, overlap, disappear, or become unusable when text is enlarged.
- Maintain sufficient contrast. As a baseline, target at least 4.5:1 for normal text and 3:1 for large text. Important non-text controls, boundaries, and graphical information required to understand the interface should generally reach 3:1 against adjacent colors.
- Do not rely on color alone to distinguish states, indicate validation, communicate chart meaning, or convey map information.
- Use readable typography, clear labels, consistent spacing, and concise error messages. When validation fails, identify the affected field and explain how to correct it.
- Verify both light and dark themes where supported, including disabled, selected, focused, error, and pressed states.

## Motion, audio, and media

- Avoid flashing content and unnecessary motion. Respect system animation settings and provide a way to pause, stop, or avoid non-essential moving content where practical.
- Do not use sound, vibration, or animation as the only way to communicate a notification or state change.
- Provide accessible names and clear playback state for media controls. Ensure captions or text alternatives are available for meaningful spoken or audio content where the product owns or supplies that content.
- Avoid autoplay behavior that prevents users from understanding or controlling the experience.

## Maps and location-based content

- Do not make a map the only way to access important location information. Provide an equivalent list, text description, or other non-map route to the same essential information.
- Give map markers and controls meaningful accessible labels, including enough context to distinguish nearby results.
- Present essential location, price, availability, and status details in text rather than encoding them only through marker color or visual placement.

## Testing expectations

For a new or materially changed screen or interaction:

1. Add or update Compose UI tests for important labels, roles, state, and actions where the test setup supports them.
2. Manually navigate the main flow with TalkBack enabled.
3. Check large font and display-size settings for clipping, overlap, and loss of actions.
4. Check keyboard or switch navigation for the key task when those input methods are applicable.
5. Review contrast and focus visibility in supported themes.
6. Use Android Accessibility Scanner or equivalent tooling as a supplementary check when available; automated scans do not replace manual assistive-technology testing.

## Known barriers and reporting

Estatia does not yet publish a complete screen-by-screen accessibility audit or inventory of known barriers. Existing screens may therefore contain accessibility defects. This policy sets the target for new and changed work; it must not be interpreted as evidence that an unaudited screen is accessible.

Report a barrier using the [Accessibility Barrier issue form](https://github.com/harrykibet/Estatia-Android/issues/new?template=accessibility_report.yml). Include the task you were trying to complete, the affected screen, the device and Android version, the assistive technology or display setting involved, and the steps that exposed the barrier. Explain what you expected and what actually happened.

Do not include personal information, account details, private property or tenant information, payment data, authentication material, or unredacted production logs. Suspected security vulnerabilities must be reported privately using [SECURITY.md](./SECURITY.md), not through a public accessibility issue.

## Pull request review

Pull requests that change user-facing UI should state the accessibility impact. Reviewers should consider semantics, touch targets, text scaling, contrast, focus order, non-gesture alternatives, and the relevant manual or automated checks. If an existing barrier cannot be addressed in the same change, document the limitation and a follow-up issue where practical.

## References

- [WCAG 2.2](https://www.w3.org/TR/WCAG22/)
- [WCAG 2.2: Contrast (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)
- [WCAG 2.2: Non-text Contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html)
- [Android accessibility principles](https://developer.android.com/guide/topics/ui/accessibility/apps)
- [Jetpack Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility)
