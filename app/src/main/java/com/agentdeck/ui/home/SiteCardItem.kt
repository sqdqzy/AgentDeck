package com.agentdeck.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentdeck.data.model.SiteCard
import com.agentdeck.data.model.SiteRegion

/**
 * One site card.
 *
 * The card background is always `surfaceContainer`; the region accent is
 * applied to the border and to the icon plate. The icon and the site name are
 * white on the accent, and the corner badge repeats the region in text so the
 * grouping never depends on colour alone.
 */
@Composable
fun SiteCardItem(
    card: SiteCard,
    loggedIn: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(card.region.accentArgb)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "cardScale",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .border(width = 2.dp, color = accent, shape = RoundedCornerShape(20.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                contentDescription =
                    "${card.name}，${card.region.badge}${if (loggedIn) "，已登录" else "，未登录"}"
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Icon plate + region badge.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = card.name.take(1).uppercase(),
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Corner badge: the region is spelled out, never colour only.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = card.region.badge,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }

                Column {
                    Text(
                        text = card.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Login status dot: green = session present, grey = none.
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (loggedIn) {
                                        Color(0xFF3DDC84)
                                    } else {
                                        Color(0xFF8A8A8A)
                                    },
                                ),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = if (loggedIn) "已登录" else "未登录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Section header separating the China block from the overseas block.
 */
@Composable
fun RegionHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The home grid.
 *
 * Two columns, 3:4 cards, 12dp gaps and 16dp padding; the China block comes
 * first, followed by the overseas block.
 */
@Composable
fun SiteGrid(
    chinaSites: List<SiteCard>,
    overseasSites: List<SiteCard>,
    loginState: Map<String, Boolean>,
    onCardClick: (SiteCard) -> Unit,
    onCardLongClick: (SiteCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            RegionHeader("中国区", "无需 VPN，蓝色标识")
        }
        items(chinaSites, key = { it.id }) { card ->
            SiteCardItem(
                card = card,
                loggedIn = loginState[card.id] == true,
                onClick = { onCardClick(card) },
                onLongClick = { onCardLongClick(card) },
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            RegionHeader("海外区", "通常需要 VPN，红色标识")
        }
        items(overseasSites, key = { it.id }) { card ->
            SiteCardItem(
                card = card,
                loggedIn = loginState[card.id] == true,
                onClick = { onCardClick(card) },
                onLongClick = { onCardLongClick(card) },
            )
        }
    }
}
