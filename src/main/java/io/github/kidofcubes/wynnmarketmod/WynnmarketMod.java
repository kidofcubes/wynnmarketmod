package io.github.kidofcubes.wynnmarketmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wynntils.core.components.Handlers;
import com.wynntils.core.components.Models;
import com.wynntils.core.text.StyledText;
import com.wynntils.handlers.item.ItemAnnotation;
import com.wynntils.handlers.item.ItemHandler;
import com.wynntils.mc.event.ScreenOpenedEvent;
import com.wynntils.models.character.type.ClassType;
import com.wynntils.models.gear.type.GearTier;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.*;
import com.wynntils.models.items.properties.*;
import com.wynntils.models.stats.type.StatActualValue;
import com.wynntils.models.stats.type.StatPossibleValues;
import com.wynntils.models.stats.type.StatType;
import com.wynntils.models.trademarket.type.TradeMarketPriceInfo;
import com.wynntils.models.wynnitem.parsing.WynnItemParseResult;
import com.wynntils.models.wynnitem.parsing.WynnItemParser;
import com.wynntils.models.wynnitem.type.ItemEffect;
import com.wynntils.screens.trademarket.TradeMarketSearchResultHolder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WynnmarketMod implements ClientModInitializer {
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "§[67] - (?:§f(?<amount>[\\d,]+) §7x )?§(?:(?:(?:c✖|a✔) §f)|f§m|f)(?<price>[\\d,]+)§7(?:§m)?²(?:§b ✮ (?<silverbullPrice>[\\d,]+)§3²)?(?: .+)?"
    );

    private static final int NEXT_PAGE_SLOT = 26;
    private static final Pattern NEXT_PAGE_PATTERN = Pattern.compile("§f§lPage (\\d+)§a >§2>§a>§2>§a>");
    private static final Pattern TRADE_MARKET_SCREEN_TITLE_PATTERN = Pattern.compile("Trade Market");

    //from wynntils/artemis src ^



//    private List<List<TradeMarketSellListing>> listings;

    /**
     *
     * @param screen
     * @return 0 indexed page
     */
    int getCurrentPage(GenericContainerScreen screen){
        ItemStack nextPageItem = screen.getScreenHandler().getSlot(NEXT_PAGE_SLOT).getStack();
        Matcher matcher = StyledText.fromComponent(nextPageItem.getName()).getMatcher(NEXT_PAGE_PATTERN);
        if(!matcher.find()) return -1;
        return Integer.parseInt(matcher.group(1))-2; //0 indexed
    }

    public List<TradeMarketSellListing> scanPage(GenericContainerScreen screen){

        List<TradeMarketSellListing> list = new ArrayList<>();
        for(int i=0;i<6;i++){
            for(int j=0;j<7;j++){
                ItemStack stack = screen.getScreenHandler().getSlot((i*9)+j).getStack();
                Optional<TradeMarketSellListing> optional = fromItemStack(stack);
                if(optional.isEmpty()) continue;
                list.add(optional.get());
            }
        }
        return list;

    }

    private boolean isTradeMarketScreen(Text title){
        return StyledText.fromComponent(title).matches(TRADE_MARKET_SCREEN_TITLE_PATTERN);
    }

    @Override
    public void onInitializeClient() {
        Handlers.WrappedScreen.registerWrappedScreen(new TradeMarketSearchResultHolder());

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof GenericContainerScreen genericContainerScreen) {


                if(isTradeMarketScreen(genericContainerScreen.getTitle())){
                    ButtonWidget buttonWidget = new ButtonWidget.Builder(Text.literal("SCAN"),button -> {
                        System.out.println("SCANNING FROM BUTTON");
                        upload(scanPage(genericContainerScreen));
                    }).dimensions(0,0,64,64).build();

                    Screens.getButtons(genericContainerScreen).add(buttonWidget);
                }
            }
        });

    }

    public Optional<TradeMarketSellListing> fromItemStack(ItemStack itemStack){
        if(itemStack.isEmpty()) return Optional.empty();
        if(itemStack.getName().getString().equalsIgnoreCase("")) return Optional.empty();

        Optional<ItemAnnotation> annotationOpt = ItemHandler.getItemStackAnnotation(itemStack);
        if(annotationOpt.isEmpty()) {
            System.out.println("was empty ");
        }
        if(annotationOpt.isEmpty()) return Optional.empty();
        if(!(annotationOpt.get() instanceof WynnItem wynnItem)) return Optional.empty();


        TradeMarketSellListing listing = new TradeMarketSellListing();
        TradeMarketPriceInfo priceInfo = Models.TradeMarket.calculateItemPriceInfo(itemStack);
        listing.price=priceInfo.price();
        listing.amount= priceInfo.amount();

        listing.item = toItemRepresentation(itemStack).orElse(null);
        if(listing.item==null) return Optional.empty();

        return Optional.of(listing);
    }

    public Optional<ItemRepresentation> toItemRepresentation(ItemStack itemStack){


        ItemRepresentation item = new ItemRepresentation();
        Optional<ItemAnnotation> annotationOpt = ItemHandler.getItemStackAnnotation(itemStack);
        if(annotationOpt.isEmpty()) return Optional.empty();
        if(!(annotationOpt.get() instanceof WynnItem wynnItem)) return Optional.empty();


        final int[] counter = {0};
        Map<StatType, StatPossibleValues> possibleValuesMap = new HashMap<>();
        WynnItemParseResult result = WynnItemParser.parseItemStack(itemStack, possibleValuesMap);

        item.stats.put("health",result.health());
        item.stats.put("level",result.level());
        if(result.durabilityMax()!=0){
            item.stats.put("max_durability",result.durabilityMax());
            item.stats.put("durability",result.durabilityCurrent());
        }
        item.stats.put("rerolls",result.rerolls());
        item.stats.put("powder_slots",result.powderSlots());
        if(result.powders()!=null){
            result.powders().forEach(powder -> {
                item.stringStats.put("powder_"+counter[0],powder.getName());
                counter[0]++;
            });
        }
        if(result.tier()!=null){
            item.stringStats.put("gear_tier",result.tier().name());
        }
        if(result.shinyStat().isPresent()){
            item.stringStats.put("shiny",result.shinyStat().get().statType().key());
            //cause its a long and isn't that important
            item.stringStats.put("shiny_value",result.shinyStat().get().value()+"");
        }
        if(result.namedEffects()!=null){
            counter[0] = 0;
            result.namedEffects().forEach(namedItemEffect -> {
                item.stringStats.put("effect_"+counter[0],namedItemEffect.type().name());
                item.stats.put("effect_value_"+counter[0],namedItemEffect.value());
                counter[0]++;
            });
        }
        if(result.effects()!=null){
            counter[0] = 0;
            result.effects().forEach(itemEffect -> {
                item.stringStats.put("custom_effect_"+counter[0],itemEffect.type());
                item.stats.put("custom_effect_value_"+counter[0],itemEffect.value());
                counter[0]++;
            });
        }
        if(result.itemType()!=null){
            item.stringStats.put("type",result.itemType());
        }

        if(result.identifications()!=null){
            result.identifications().forEach(identification -> {
                item.stats.put(identification.statType().getApiName(),identification.value());
            });
        }


        //todo get powder special from item
        if(wynnItem instanceof PowderedItemProperty powderedItemProperty){
            item.stats.put("powder_slots",powderedItemProperty.getPowderSlots());
            counter[0]=0;
            powderedItemProperty.getPowders().forEach(powder -> {
                item.stringStats.put("powder_"+counter[0],powder.getName());
                counter[0]++;
            });
        }

        if(wynnItem instanceof DurableItemProperty durableItemProperty){
            item.stats.put("max_durability",durableItemProperty.getDurability().max());
            item.stats.put("durability",durableItemProperty.getDurability().current());
        }

        if(wynnItem instanceof LeveledItemProperty leveledItemProperty){
            item.stats.put("level",leveledItemProperty.getLevel());
        }

        if(wynnItem instanceof CraftedItemProperty craftedItemProperty){
            for (StatActualValue stat : craftedItemProperty.getIdentifications()){
                item.stats.put(stat.statType().getApiName(), stat.value());
//                item.stats.put(stat.statType().getApiName()+"_FROM_API", stat.value());
//                item.stats.put(stat.statType().getInternalRollName()+"_FROM_INTERNAL_ROLL", stat.value());
//                item.stats.put(stat.statType().getKey()+"_FROM_KEY", stat.value());
//                item.stats.put(stat.statType().getDisplayName()+"_FROM_DISPLAY", stat.value());
//                item.stats.put(stat.statType().getSpecialStatType().name()+"_FROM_SPECIAL", stat.value());
            }

            ClassType classType = craftedItemProperty.getRequiredClass();
            if(classType==null){
                classType=ClassType.NONE;
            }
            item.stringStats.put("class_req",classType.getName());

        }
        if(wynnItem instanceof NamedItemProperty namedItemProperty){
            item.stringStats.put("name",namedItemProperty.getName());
            item.name=namedItemProperty.getName();

        }

        if(wynnItem instanceof NumberedTierItemProperty numberedTierItemProperty){
            item.stats.put("tier",numberedTierItemProperty.getTier());
        }

        if(wynnItem instanceof GearTierItemProperty gearTierItemProperty){
            item.stringStats.put("gear_tier",gearTierItemProperty.getGearTier().name());
        }
        if(wynnItem instanceof GearTypeItemProperty gearTypeItemProperty){
            item.stringStats.put("type",gearTypeItemProperty.getGearType().name());
        }
        if(wynnItem instanceof RerollableItemProperty rerollableItemProperty){
            item.stats.put("rerolls",rerollableItemProperty.getRerollCount());
        }
        if(wynnItem instanceof UsesItemProperty usesItemProperty){
            item.stats.put("max_uses",usesItemProperty.getUses().max());
            item.stats.put("uses",usesItemProperty.getUses().current());
        }
        if(wynnItem instanceof CountedItemProperty countedItemProperty){
            item.stats.put("count",countedItemProperty.getCount());
        }
        if(wynnItem instanceof SetItemProperty setItemProperty){}
        if(wynnItem instanceof TargetedItemProperty targetedItemProperty){
            item.stringStats.put("target",targetedItemProperty.getTarget());
        }

        if(wynnItem instanceof ProfessionItemProperty professionItemProperty){
            professionItemProperty.getProfessionTypes().forEach(professionType -> {
                item.stringStats.put(professionType.name(),"");
            });
        }
        if(wynnItem instanceof ShinyItemProperty shinyItemProperty){
            if(shinyItemProperty.getShinyStat().isPresent()){
                item.stringStats.put("shiny",shinyItemProperty.getShinyStat().get().statType().key());
                //doesn't really matter that much, and it's a long
                item.stringStats.put("shiny_value",shinyItemProperty.getShinyStat().get().value()+"");
            }
        }




        if(wynnItem instanceof GearItem gearItem){

            if(gearItem.getGearTier()==GearTier.CRAFTED){ //should be a CraftedGearItem
                System.err.println("shouldn't be possible, gear item thats crafted but not craftedgearitem");
                return Optional.empty();
            }


            for (StatActualValue stat : gearItem.getIdentifications()){
                if(stat.statType().getSpecialStatType()==StatType.SpecialStatType.NONE){
                    item.stats.put(stat.statType().getApiName(),stat.value());
                }else{
                    item.stats.put(stat.statType().getApiName(),stat.value());
                }
            }
//            var stats = Models.Stat.getSortedStats(gearItem.getItemInfo(), StatListOrdering.DEFAULT);
//            var gearItemInstanceOption = gearItem.getItemInstance();
//            if(gearItemInstanceOption.isEmpty()) return null;
//            var gearItemInstance = gearItemInstanceOption.get();


//            for (StatType statType : stats){
//                StatActualValue actualValue = gearItemInstance.getActualValue(statType);
//                StatPossibleValues possibleValues = gearItem.getItemInfo().getPossibleValues(statType);
//                if (actualValue == null || possibleValues == null) {
//                    return null;
//                }
//                item.stats.put(statType.getApiName(),actualValue.value());
//            }
            if(gearItem.isUnidentified()){
                item.name="Unidentified "+gearItem.getName();
            }else{
                item.name=gearItem.getName();
            }


        }else if(wynnItem instanceof CraftedConsumableItem craftedConsumableItem){
            item.name="Crafted Consumable";
            for (StatActualValue stat : craftedConsumableItem.getIdentifications()) item.stats.put(stat.statType().getApiName(), stat.value());
            for (ItemEffect effect : craftedConsumableItem.getEffects()) item.stats.put(effect.type(), effect.value());
        }else if(wynnItem instanceof CraftedGearItem craftedGearItem){
            item.name="Crafted "+craftedGearItem.getGearType().name(); //for searching

            if(craftedGearItem.getAttackSpeed().isPresent()){
                item.stats.put("base_attack_speed",craftedGearItem.getAttackSpeed().get().getOffset());
            }
            craftedGearItem.getDamages().forEach(pair -> {
                item.stats.put(pair.a().name()+"-min",pair.b().low());
                item.stats.put(pair.a().name()+"-max",pair.b().high());
            });
            craftedGearItem.getDefences().forEach(pair -> {
                item.stats.put(pair.a().getDisplayName()+"-defense-DISPLAY",pair.b());
                item.stats.put(pair.a().name()+"-defense-DEFAULT",pair.b());
            });
            item.stats.put("health",craftedGearItem.getHealth());
            item.stats.put("effect_strength",craftedGearItem.getEffectStrength());


        } else if (wynnItem instanceof GearBoxItem gearBoxItem){
            //shouldn't be possible
        }else if(wynnItem instanceof UnknownGearItem unknownGearItem){
            //????
        } else if (wynnItem instanceof GatheringToolItem gatheringItem) {
            item.name=gatheringItem.getToolProfile().toolType().name();
        } else if (wynnItem instanceof EmeraldPouchItem emeraldPouchItem){
            item.name="Emerald Pouch tier "+emeraldPouchItem.getTier();
        } else if (wynnItem instanceof IngredientItem ingredientItem){
            item.name = ingredientItem.getIngredientInfo().name();
        } else if (wynnItem instanceof MaterialItem materialItem){
            item.name = materialItem.getMaterialProfile().getResourceType()+" T"+materialItem.getQualityTier();
        } else if (wynnItem instanceof HorseItem horseItem){
            item.name = "Horse T"+horseItem.getTier().getNumeral();
            item.stats.put("horse_level",horseItem.getLevel().current());
            item.stats.put("horse_xp",horseItem.getXp().current());
        } else if (wynnItem instanceof PowderItem powderItem){
            item.name = powderItem.getPowderProfile().element().getName()+" Powder T"+powderItem.getTier();
        } else if (wynnItem instanceof PotionItem potionItem){
            item.name = potionItem.getType().name()+" Potion";
        } else if (wynnItem instanceof MultiHealthPotionItem multiHealthPotionItem){
            //shouldn't be possible
        } else if (wynnItem instanceof TeleportScrollItem teleportScrollItem){
            item.name = teleportScrollItem.getDestination()+" Teleport Scroll";
        } else if (wynnItem instanceof DungeonKeyItem dungeonKeyItem){
            item.name = dungeonKeyItem.getDungeon().name()+" Key";
        } else if (wynnItem instanceof AmplifierItem amplifierItem){
            item.name = "Corkian Amplifier T"+amplifierItem.getTier();
        } else if (wynnItem instanceof TrinketItem trinketItem){
            item.name = trinketItem.getTrinketName();
        } else if(wynnItem instanceof MiscItem miscItem) {
            item.name=miscItem.getName().getStringWithoutFormatting();
//            if(miscItem.isQuestItem()||miscItem.isUntradable()){
//                return null;
//            }
        }else{
        }

//        if("none".equals(item.stringStats.get("class_req"))){
//            item.stringStats.remove("class_req");
//        }
//        if(item.name==null&&item.stringStats.containsKey("name")){ //don't know if this ever happens
//            item.name=item.stringStats.get("name");
//        }
//        if(item.name!=null&&item.name.equals(item.stringStats.get("name"))){
//            item.stringStats.remove("name");
//        }
        item.stringStats.entrySet().removeIf(entry -> entry.getValue()==null);

        return Optional.of(item);
    }


    public static class ItemRepresentation{
        public String name="NONAME";
        public Map<String, Integer> stats = new HashMap<>();
        public Map<String, String> stringStats = new HashMap<>();

    }
    public static class TradeMarketSellListing {
        public ItemRepresentation item;
        public int price;
        public int amount;
    }
    public static class UploadThing {
        public List<TradeMarketSellListing> listings;
        public long timestamp; //seconds
    }


    public static final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static void upload(List<TradeMarketSellListing> listings){
        UploadThing uploadThing = new UploadThing();
        uploadThing.listings=listings;

        uploadThing.timestamp= Instant.now().getEpochSecond();
        String magicDatum = gson.toJson(uploadThing);
        System.out.println(magicDatum);
        //todo implement
    }
}
