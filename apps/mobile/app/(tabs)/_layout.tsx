import { Tabs } from 'expo-router'
import { StyleSheet, View, type ColorValue } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { BlurView } from 'expo-blur'
import { colors } from '@/theme'

/** Frosted translucent tab bar — content scrolls underneath. */
function TabBarBackground() {
  return (
    <View style={StyleSheet.absoluteFill}>
      <BlurView intensity={50} tint="dark" style={StyleSheet.absoluteFill} />
      <View style={[StyleSheet.absoluteFill, styles.tint]} />
    </View>
  )
}

const icon =
  (name: keyof typeof Ionicons.glyphMap, outline: keyof typeof Ionicons.glyphMap) =>
  ({ focused, color, size }: { focused: boolean; color: ColorValue; size: number }) =>
    <Ionicons name={focused ? name : outline} size={size} color={color} />

export default function TabsLayout() {
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        // Bright inactive tabs stay legible over poster art bleeding through
        // the translucent bar; the accent marks the active tab. Label color is
        // left to the tint so active/inactive track the icon.
        tabBarActiveTintColor: colors.accent,
        tabBarInactiveTintColor: colors.text,
        tabBarLabelStyle: {
          fontWeight: '600',
        },
        tabBarStyle: {
          position: 'absolute',
          backgroundColor: 'transparent',
          borderTopColor: colors.glassBorder,
          borderTopWidth: StyleSheet.hairlineWidth,
        },
        tabBarBackground: TabBarBackground,
        sceneStyle: { backgroundColor: colors.background },
      }}
    >
      <Tabs.Screen
        name="index"
        options={{ title: 'Home', tabBarIcon: icon('home', 'home-outline') }}
      />
      <Tabs.Screen
        name="library"
        options={{ title: 'Library', tabBarIcon: icon('bookmark', 'bookmark-outline') }}
      />
      <Tabs.Screen
        name="downloads"
        options={{ title: 'Downloads', tabBarIcon: icon('arrow-down-circle', 'arrow-down-circle-outline') }}
      />
      {/* Route stays `addons` (other code navigates by this path); presents as Settings. */}
      <Tabs.Screen
        name="addons"
        options={{ title: 'Settings', tabBarIcon: icon('settings', 'settings-outline') }}
      />
    </Tabs>
  )
}

const styles = StyleSheet.create({
  tint: { backgroundColor: 'rgba(12,14,19,0.55)' },
})
