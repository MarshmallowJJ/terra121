package io.github.terra121.populator;

import java.util.Random;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.worldgen.populator.ICubicPopulator;
import io.github.terra121.dataset.Heights;
import io.github.terra121.dataset.OpenStreetMaps;
import io.github.terra121.projection.GeographicProjection;
import net.minecraft.block.BlockColored;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

public class RoadGenerator implements ICubicPopulator {
	
    private static final IBlockState ASPHALT = Blocks.CONCRETE.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.GRAY);
    private static final IBlockState WATER_SOURCE = Blocks.WATER.getDefaultState();
    //private static final IBlockState WATER_RAMP = Blocks.WATER.getDefaultState().withProperty(BlockLiquid.LEVEL, );
    private static final IBlockState WATER_BEACH = Blocks.DIRT.getDefaultState();

    private OpenStreetMaps osm;
    private Heights heights;
    private GeographicProjection projection;

    public RoadGenerator(OpenStreetMaps osm, Heights heights, GeographicProjection proj) {
        this.osm = osm;
        this.heights = heights;
        projection = proj;
    }

    public void generate(World world, Random rand, CubePos pos, Biome biome) {
    	
    	int cubeX = pos.getX(), cubeY = pos.getY(), cubeZ = pos.getZ();
    	
        Set<OpenStreetMaps.Edge> edges = osm.chunkStructures(cubeX, cubeZ);
		
        if(edges!=null) { 
        	
        	//rivers done before roads
        	for(OpenStreetMaps.Edge e: edges) {
	            if(e.type == OpenStreetMaps.Type.RIVER) {
	            	placeEdge(e, world, cubeX, cubeY, cubeZ, 5, (dis, bpos) -> riverState(world, dis, bpos));
	            }
	        }
        	
	        for(OpenStreetMaps.Edge e: edges) {
	            if(e.type == OpenStreetMaps.Type.MAJOR || e.type == OpenStreetMaps.Type.HIGHWAY) {
	            	placeEdge(e, world, cubeX, cubeY, cubeZ, 1.5*e.lanes, (dis, bpos) -> ASPHALT);
	            }
	        }
        }
    }
    
    private IBlockState riverState(World world, double dis, BlockPos pos) {
		IBlockState prev = world.getBlockState(pos);
		if(dis>2) {
			if(!prev.getBlock().equals(Blocks.AIR))
				return null;
            IBlockState under = world.getBlockState(pos.down());
            if(under.getBlock() instanceof BlockLiquid)
                return null;
			return WATER_BEACH;
		}
		else return WATER_SOURCE;
    }
    
    private void placeEdge(OpenStreetMaps.Edge e, World world, int cubeX, int cubeY, int cubeZ, double r, BiFunction<Double, BlockPos, IBlockState> state) {
        double x0 = 0;
        double b = r;
        if(Math.abs(e.slope)>=0.000001) {
            x0 = r/Math.sqrt(1 + 1 / (e.slope * e.slope));
            b = (e.slope < 0 ? -1 : 1) * x0 * (e.slope + 1.0 / e.slope);
        }

        double j = e.slon - (cubeX*16);
        double k = e.elon - (cubeX*16);
        double off = e.offset - (cubeZ*16) + e.slope*(cubeX*16);
        
        if(j>k) {
            double t = j;
            j = k;
            k = t;
        }

        double ij = j-r;
        double ik = k+r;
        
        if(j<=0) {
        	j=0;
        	//ij=0;
        }
        if(k>=16) {
        	k=16;
        	//ik = 16;
        }

        int is = (int)Math.floor(ij);
        int ie = (int)Math.floor(ik);

        for(int x=is; x<=ie; x++) {
            double X = x;
            double ul = bound(X, e.slope, j, k, r, x0, b, 1) + off; //TODO: save these repeated values
            double ur = bound(X+1, e.slope, j, k, r, x0, b, 1) + off;
            double ll = bound(X, e.slope, j, k, r, x0, b, -1) + off;
            double lr = bound(X+1, e.slope, j, k, r, x0, b,-1) + off;

            double from = Math.min(Math.min(ul,ur),Math.min(ll,lr));
            double to = Math.max(Math.max(ul,ur),Math.max(ll,lr));
            
            if(from==from) {
                int ifrom = (int)Math.floor(from);
                int ito = (int)Math.floor(to);

                if(ifrom <= -1*16)
                    ifrom = 1 - 16;
                if(ito >= 16*2)
                    ito = 16*2-1;

                for(int z=ifrom; z<=ito; z++) {
                    //get the part of the center line i am tangent to (i hate high school algebra!!!)
                    double Z = z;
                    double mainX = X;
                    if(Math.abs(e.slope)>=0.000001)
                        mainX = (Z + X/e.slope - off)/(e.slope + 1/e.slope);

                    /*if(mainX<j) mainX = j;
                    else if(mainX>k) mainX = k;*/

                    double mainZ = e.slope*mainX + off;
                    
                    //get distance to closest point
                    double distance = mainX-X;
                	distance *= distance;
                	double t = mainZ-Z;
                	distance += t*t;
                	distance = Math.sqrt(distance);

                    double[] geo = projection.toGeo(mainX + cubeX*(16), mainZ + cubeZ*(16));
                    int y = (int)Math.floor(heights.estimateLocal(geo[0], geo[1]) - cubeY*16);

                    if (y >= 0 && y < 16) { //if not in this range, someone else will handle it
                    	
                    	BlockPos surf = new BlockPos(x + cubeX * 16, y + cubeY * 16, z + cubeZ * 16);
                    	IBlockState bstate = state.apply(distance, surf);
                    	
                    	if(bstate!=null) {
		                	world.setBlockState(surf, bstate);
		
		                    //clear the above blocks (to a point, we don't want to be here all day)
		                    IBlockState defState = Blocks.AIR.getDefaultState();
		                    for (int ay = y + 1; ay < 16 * 2 && world.getBlockState(new BlockPos(x + cubeX * 16, ay + cubeY * 16, z + cubeZ * 16)) != defState; ay++) {
		                        world.setBlockState(new BlockPos(x + cubeX * 16, ay + cubeY * 16, z + cubeZ * 16), defState);
		                    }
                        }
                    }
                }
            }
        }
    }

    private static double bound(double x, double slope, double j, double k, double r, double x0, double b, double sign) {
        double slopesign = sign*(slope<0?-1:1);

        if(x < j - slopesign*x0) { //left circle
            return slope*j + sign*Math.sqrt(r*r-(x-j)*(x-j));
        }
        if(x > k - slopesign*x0) { //right circle
            return slope*k + sign*Math.sqrt(r*r-(x-k)*(x-k));
        }
        return slope*x + sign*b;
    }
}