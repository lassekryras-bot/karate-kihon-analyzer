package dk.lasse.karatecliprecorder.profile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.AppIconView
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.StickyHeaderPageLayout
import dk.lasse.karatecliprecorder.SubPageHeader

/** Shared create/edit secondary screen. It only mutates profile identity fields. */
class ProfileEditorView(
    context: Context,
    private val repository: ProfileRepository,
    private val editing: Profile?,
    private val onBack: () -> Unit,
    private val onSaved: (Profile) -> Unit,
) : FrameLayout(context) {
    private val original = editing
    private var gender = editing?.gender ?: Gender.FEMALE
    private var ageGroup = editing?.ageGroup ?: AgeGroup.ADULT
    private var avatarBaseId = editing?.avatarBaseId ?: Profile.AVATAR_BASE_IDS.first()
    private var skinPosition = editing?.skinTonePosition ?: 0.5f
    private var hairPosition = editing?.hairColorPosition ?: 0.35f
    private var beltRank = editing?.beltRank ?: BeltRank.WHITE
    private val preview = AvatarView(context).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_card_surface))
    }
    private val nameInput = EditText(context)
    private val genderChoices = LinearLayout(context)
    private val ageChoices = LinearLayout(context)
    private val carousel = AvatarCarouselLayout(context, ::moveCharacter)
    private val beltChoices = LinearLayout(context)
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val createMode = editing == null
    private val subHeader = SubPageHeader(
        context = context,
        title = if (createMode) "Add new profile" else "Edit profile",
        subtitle = if (createMode) {
            "Create a trainee profile to save progress and personal results."
        } else null,
        onBack = onBack,
    )

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))
        addView(StickyHeaderPageLayout(
            context = context,
            header = subHeader,
            body = content,
            horizontalContentPaddingDp = 12,
            topContentPaddingDp = 10,
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildContent()
        updatePreview()
    }

    private fun buildContent() {
        content.addView(preview, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 260.dp()).apply {
            topMargin = 4.dp()
            bottomMargin = 12.dp()
        })
        content.addView(sectionCard().apply {
            addView(context.profileText("Name", 16f, bold = true))
            addView(nameInput.apply {
                setText(editing?.name.orEmpty())
                hint = "Trainee name"
                textSize = 16f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                maxLines = 1
                imeOptions = EditorInfo.IME_ACTION_DONE
                setSingleLine(true)
                background = fieldBackground()
                setPadding(12.dp(), 0, 12.dp(), 0)
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 52.dp()).apply { topMargin = 8.dp() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(choiceBlock("Gender", genderChoices), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8.dp() })
                addView(choiceBlock("Age group", ageChoices), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8.dp() })
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 18.dp() })
        })
        renderIdentityChoices()

        content.addView(characterSection(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp() })
        content.addView(sliderSection("Skin tone", true), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp() })
        content.addView(sliderSection("Hair color", false), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp() })
        content.addView(beltSection(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp() })
        content.addView(context.primaryProfileButton(if (createMode) "✓  Save profile" else "✓  Save changes", ::save),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 54.dp()).apply { topMargin = 16.dp() })

    }

    private fun choiceBlock(title: String, container: LinearLayout) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(context.profileText(title, 16f, bold = true))
        addView(container.apply { orientation = LinearLayout.HORIZONTAL }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 46.dp()).apply {
            topMargin = 8.dp()
        })
    }

    private fun renderIdentityChoices() {
        genderChoices.removeAllViews()
        Gender.entries.forEachIndexed { index, value ->
            genderChoices.addView(context.outlinedChoice(value.displayName, gender == value) {
                gender = value
                renderIdentityChoices()
            }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { if (index > 0) marginStart = 5.dp() })
        }
        ageChoices.removeAllViews()
        AgeGroup.entries.forEachIndexed { index, value ->
            ageChoices.addView(context.outlinedChoice(value.displayName, ageGroup == value) {
                ageGroup = value
                renderIdentityChoices()
            }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { if (index > 0) marginStart = 5.dp() })
        }
    }

    private fun characterSection() = sectionCard().apply {
        clipToPadding = false
        foreground = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = 14.dp().toFloat()
            setStroke(1.dp(), ContextCompat.getColor(context, R.color.app_border))
        }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(context.profileText("Choose character", 16f, bold = true), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(6.dp(), 0, 6.dp(), 0)
                minimumHeight = 48.dp()
                minimumWidth = 80.dp()
                isClickable = true
                isFocusable = true
                contentDescription = "See all characters"
                addView(context.profileText("See all", 14f).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                addView(AppIconView(context, AppIcon.CHEVRON_RIGHT).apply {
                    setIconColor(ContextCompat.getColor(context, R.color.app_text_secondary))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(16.dp(), 16.dp()).apply { marginStart = 4.dp() })
                setOnClickListener { showAllCharacters() }
            })
        })
        addView(carousel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 148.dp()).apply {
            topMargin = 10.dp()
            marginStart = -14.dp()
            marginEnd = -14.dp()
        })
        addView(context.profileText("Swipe left or right to browse characters.", 13f, gravity = Gravity.CENTER).apply {
            setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp() })
        renderCarousel()
    }

    private fun renderCarousel() {
        carousel.removeAllViews()
        val selectedIndex = Profile.AVATAR_BASE_IDS.indexOf(avatarBaseId)
        AvatarCarouselModel.visibleBaseIds(selectedIndex).forEachIndexed { visibleIndex, baseId ->
            val selected = visibleIndex == 2
            carousel.addView(FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.app_card_surface))
                    cornerRadius = 10.dp().toFloat()
                }
                clipToOutline = true
                isSelected = selected
                addView(AvatarView(context).apply {
                    setAvatar(baseId, skinPosition, hairPosition, beltRank)
                    portraitCrop = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                isClickable = true
                isFocusable = true
                contentDescription = if (selected) "$baseId selected" else "Select $baseId"
                setOnClickListener { carousel.selectOffset(visibleIndex - 2) }
            }, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    }

    private fun moveCharacter(delta: Int) {
        val current = Profile.AVATAR_BASE_IDS.indexOf(avatarBaseId)
        avatarBaseId = Profile.AVATAR_BASE_IDS[AvatarCarouselModel.move(current, delta)]
        updatePreview()
        renderCarousel()
    }

    private fun showAllCharacters() {
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
        }
        fun renderGrid() {
            grid.removeAllViews()
            Profile.AVATAR_BASE_IDS.chunked(3).forEach { rowIds ->
                grid.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowIds.forEachIndexed { index, baseId ->
                        val selected = baseId == avatarBaseId
                        addView(FrameLayout(context).apply {
                            background = GradientDrawable().apply {
                                setColor(ContextCompat.getColor(context, R.color.app_card_surface))
                                cornerRadius = 10.dp().toFloat()
                            }
                            clipToOutline = true
                            foreground = GradientDrawable().apply {
                                setColor(android.graphics.Color.TRANSPARENT)
                                cornerRadius = 10.dp().toFloat()
                                setStroke((if (selected) 2 else 1).dp(), ContextCompat.getColor(context,
                                    if (selected) R.color.app_accent else R.color.app_border))
                            }
                            addView(AvatarView(context).apply {
                                portraitCrop = true
                                setAvatar(baseId, skinPosition, hairPosition, beltRank)
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                            isSelected = selected
                            isClickable = true
                            isFocusable = true
                            contentDescription = "Character ${Profile.AVATAR_BASE_IDS.indexOf(baseId) + 1}" + if (selected) ", selected" else ""
                            setOnClickListener {
                                avatarBaseId = baseId
                                updatePreview()
                                renderCarousel()
                                renderGrid()
                            }
                        }, LinearLayout.LayoutParams(0, 136.dp(), 1f).apply {
                            if (index > 0) marginStart = 8.dp()
                        })
                    }
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 8.dp()
                })
            }
        }
        renderGrid()
        val scroller = android.widget.ScrollView(context).apply { addView(grid) }
        AlertDialog.Builder(context)
            .setTitle("Choose character")
            .setView(scroller)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun sliderSection(title: String, skin: Boolean) = sectionCard().apply {
        addView(context.profileText(title, 16f, bold = true))
        addView(if (skin) context.skinGradientView() else context.hairGradientView(),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 30.dp()).apply { topMargin = 12.dp() })
        addView(SeekBar(context).apply {
            max = 1000
            progress = (((if (skin) skinPosition else hairPosition) * max).toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (skin) skinPosition = progress / 1000f else hairPosition = progress / 1000f
                    updatePreview()
                    renderCarousel()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp() })
    }

    private fun beltSection() = sectionCard().apply {
        addView(context.profileText("Current belt", 16f, bold = true))
        addView(beltChoices.apply { orientation = LinearLayout.HORIZONTAL },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 56.dp()).apply { topMargin = 10.dp() })
        addView(context.profileText("Accessories match your belt color.", 13f).apply {
            setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
            setPadding(0, 8.dp(), 0, 0)
        })
        renderBelts()
    }

    private fun renderBelts() {
        beltChoices.removeAllViews()
        BeltRank.entries.forEachIndexed { index, rank ->
            beltChoices.addView(beltChoice(rank, rank == beltRank) {
                beltRank = rank
                updatePreview()
                renderCarousel()
                renderBelts()
            }, LinearLayout.LayoutParams(0, 56.dp(), 1f).apply { if (index > 0) marginStart = 3.dp() })
        }
    }

    private fun beltChoice(rank: BeltRank, selected: Boolean, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumWidth = 48.dp()
        minimumHeight = 48.dp()
        val accent = ContextCompat.getColor(context, R.color.app_accent)
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.app_card_surface))
            cornerRadius = 10.dp().toFloat()
            setStroke(2.dp().takeIf { selected } ?: 1.dp(), if (selected) accent else ContextCompat.getColor(context, R.color.app_border))
        }
        addView(AppIconView(
            context,
            AppIcon.KARATE_BELT,
            sizeDp = 36,
            tint = ColorStateList.valueOf(beltIconColor(rank)),
        ), LinearLayout.LayoutParams(36.dp(), 36.dp()))
        contentDescription = "${rank.displayName} belt${if (selected) ", selected" else ""}"
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun beltIconColor(rank: BeltRank): Int = if (rank == BeltRank.WHITE) {
        AvatarPalette.shade(AvatarPalette.belt(rank), 0.72f)
    } else {
        AvatarPalette.belt(rank)
    }

    private fun updatePreview() {
        preview.setAvatar(avatarBaseId, skinPosition, hairPosition, beltRank)
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
        if (name.isBlank()) {
            nameInput.error = "Enter a name"
            nameInput.requestFocus()
            return
        }
        val now = System.currentTimeMillis()
        val profile = if (original == null) Profile(
            name = name,
            gender = gender,
            ageGroup = ageGroup,
            avatarBaseId = avatarBaseId,
            skinTonePosition = skinPosition,
            hairColorPosition = hairPosition,
            beltRank = beltRank,
            createdAt = now,
            updatedAt = now,
        ) else original.copy(
            name = name,
            gender = gender,
            ageGroup = ageGroup,
            avatarBaseId = avatarBaseId,
            skinTonePosition = skinPosition,
            hairColorPosition = hairPosition,
            beltRank = beltRank,
            updatedAt = now,
        )
        val saved = if (original == null) repository.createProfile(profile) else repository.updateProfile(profile)
        if (original == null) repository.switchActiveProfile(saved.id)
        Toast.makeText(context, if (original == null) "Profile saved" else "Changes saved", Toast.LENGTH_SHORT).show()
        onSaved(saved)
    }

    private fun sectionCard() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.app_card_surface))
            cornerRadius = 14.dp().toFloat()
            setStroke(1.dp(), ContextCompat.getColor(context, R.color.app_border))
        }
    }

    private fun fieldBackground() = GradientDrawable().apply {
        setColor(ContextCompat.getColor(context, R.color.app_card_surface))
        cornerRadius = 9.dp().toFloat()
        setStroke(1.dp(), ContextCompat.getColor(context, R.color.app_text_secondary))
    }

    private fun Int.dp() = context.dp(this)
}
