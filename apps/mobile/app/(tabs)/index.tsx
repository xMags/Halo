import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native'
import { useAddons } from '@/queries'
import { colors, spacing } from '@/theme'
import { CatalogRow } from '@/components/CatalogRow'

export default function HomeScreen() {
  const { data: addons, isLoading, isError } = useAddons()

  if (isLoading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator color={colors.accent} size="large" />
      </View>
    )
  }
  if (isError) {
    return (
      <View style={styles.center}>
        <Text style={styles.message}>Could not reach your Halo server.</Text>
      </View>
    )
  }

  // Catalogs that demand extra parameters (search, genre pickers) can't render
  // as plain rows; only parameterless ones belong on Home.
  const rows = (addons ?? []).flatMap((addon) =>
    addon.manifest.catalogs
      .filter((catalog) => !(catalog.extra ?? []).some((e) => e.isRequired))
      .filter((catalog) => !(catalog.extraRequired ?? []).length)
      .map((catalog) => ({
        key: `${addon.transportUrl}/${catalog.type}/${catalog.id}`,
        transportUrl: addon.transportUrl,
        addonName: addon.manifest.name,
        type: catalog.type,
        catalogId: catalog.id,
        catalogName: catalog.name,
      })),
  )

  if (rows.length === 0) {
    return (
      <View style={styles.center}>
        <Text style={styles.message}>No catalogs yet — add an addon in the Addons tab.</Text>
      </View>
    )
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {rows.map((row) => (
        <CatalogRow
          key={row.key}
          transportUrl={row.transportUrl}
          addonName={row.addonName}
          type={row.type}
          catalogId={row.catalogId}
          catalogName={row.catalogName}
        />
      ))}
    </ScrollView>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  content: {
    paddingVertical: spacing.md,
  },
  center: {
    flex: 1,
    backgroundColor: colors.background,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.lg,
  },
  message: {
    color: colors.textDim,
    textAlign: 'center',
    fontSize: 15,
  },
})
